package dev.alessiodam.mcmods.ccstudio.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.alessiodam.mcmods.ccstudio.Log;
import dev.alessiodam.mcmods.ccstudio.StudioServer;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerHandle;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerInput;
import dev.alessiodam.mcmods.ccstudio.platform.Platform;
import dev.alessiodam.mcmods.ccstudio.platform.Settings;
import dev.alessiodam.mcmods.ccstudio.util.Names;
import dev.alessiodam.mcmods.ccstudio.web.Json;
import dev.alessiodam.mcmods.ccstudio.web.StudioSocket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class Session {
    private static final int KEY_ENTER = 257;
    private static final int MAX_EVENTS_PER_MESSAGE = 512;
    private static final int MAX_QUEUED_INPUT_EVENTS = 2048;
    private static final int MAX_INPUT_EVENTS_PER_TICK = 256;
    private static final int MAX_PASTE_CHARACTERS = 512;
    private static final int MAX_CHAR_CODEPOINTS = 8;
    private static final int MAX_QUEUED_CONTROLS = 16;
    private static final int TERMINAL_INTERVAL_TICKS = 2;
    private static final int RUN_DELAY_TICKS = 10;
    private static final int RUN_TIMEOUT_TICKS = 400;
    private static final int MIN_MESSAGE_BYTES = 1024 * 1024;
    private static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_PENDING_PAIRINGS = 8;
    private static final int MAX_TRUSTED_DEVICES = 16;
    private static final long PAIRING_LIFETIME_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long PAIRING_NOTICE_INTERVAL_MILLIS = 5_000;
    private static final long RESYNC_WINDOW_MILLIS = 10_000;

    private final Platform platform;
    private final Settings settings;
    private final String token;
    private final String assetToken;
    private final int computerId;
    private final int maxMessageBytes;
    private final long created = System.currentTimeMillis();
    private final SessionFileSystem fileSystem;
    private final Set<StudioSocket> sockets = ConcurrentHashMap.newKeySet();
    private final Map<String, Pairing> pairings = new ConcurrentHashMap<>();
    private final Set<String> trustedDevices = ConcurrentHashMap.newKeySet();
    private final Queue<Pairing> pairingNotices = new ConcurrentLinkedQueue<>();
    private final RateLimiter requests = new RateLimiter(100, 300);
    private final RateLimiter inputCost = new RateLimiter(600, 1800);
    private final RateLimiter frames = new RateLimiter(200, 400);
    private final RateLimiter bytes;
    private final RateLimiter pairingStarts = new RateLimiter(0.5, 8);
    private final Queue<JsonArray> inputQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedInputEvents = new AtomicInteger();
    private final Queue<PendingControl> controlQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedControls = new AtomicInteger();
    private final AtomicBoolean releaseRequested = new AtomicBoolean();

    private volatile @Nullable ComputerHandle computer;
    private @Nullable ComputerInput input;
    private @Nullable ComputerHandle inputOwner;
    private volatile @Nullable String label;
    private volatile String state;
    private @Nullable String frame;
    private volatile long lastActive = System.currentTimeMillis();
    private volatile boolean closed;
    private long lastPairingNotice;
    private @Nullable String pendingRun;
    private int pendingRunTicks;

    Session(StudioServer studio, ComputerHandle computer) {
        this.platform = studio.platform();
        this.settings = studio.settings();
        this.token = Tokens.newToken();
        this.assetToken = Tokens.newToken();
        this.computerId = computer.id();
        var storage = platform.openStorage(computer, () -> this.computer);
        this.maxMessageBytes = (int) Math.clamp(storage.capacity() * 4 / 3 + MIN_MESSAGE_BYTES, MIN_MESSAGE_BYTES, MAX_MESSAGE_BYTES);
        this.bytes = new RateLimiter(8.0 * 1024 * 1024, Math.max(16.0 * 1024 * 1024, 2.0 * maxMessageBytes));
        this.fileSystem = new SessionFileSystem(storage, settings.showRom() ? platform.openRom() : null);
        attach(computer);
        this.state = describeState();
    }

    public String token() {
        return token;
    }

    public String assetToken() {
        return assetToken;
    }

    public int computerId() {
        return computerId;
    }

    public @Nullable String label() {
        return label;
    }

    public SessionFileSystem fileSystem() {
        return fileSystem;
    }

    public int maxMessageBytes() {
        return maxMessageBytes;
    }

    public boolean isClosed() {
        return closed;
    }

    public int connectionCount() {
        return sockets.size();
    }

    public String title() {
        return Names.describe(computerId, label);
    }

    public boolean allowRequest() {
        return requests.tryAcquire(1);
    }

    public boolean allowFrame(int size) {
        return frames.tryAcquire(1) && bytes.tryAcquire(size);
    }

    public boolean isTrusted(@Nullable String deviceToken) {
        return !closed && Tokens.isToken(deviceToken) && trustedDevices.contains(Tokens.hash(deviceToken));
    }

    public @Nullable Pairing pairing(@Nullable String pairingId) {
        if (closed || !Tokens.isToken(pairingId)) return null;
        var pairing = pairings.get(Tokens.hash(pairingId));
        if (pairing == null || pairing.isExpired(System.currentTimeMillis(), PAIRING_LIFETIME_MILLIS)) return null;
        return pairing;
    }

    public @Nullable NewPairing startPairing(String address, String browser) {
        if (closed) return null;
        prunePairings();
        if (pairings.size() >= MAX_PENDING_PAIRINGS || !pairingStarts.tryAcquire(1)) return null;
        var pairingId = Tokens.newToken();
        var pairing = new Pairing(Tokens.newPairingCode(), address, browser);
        pairings.put(Tokens.hash(pairingId), pairing);
        pairingNotices.add(pairing);
        return new NewPairing(pairingId, pairing);
    }

    public @Nullable String completePairing(@Nullable String pairingId) {
        if (closed || !Tokens.isToken(pairingId)) return null;
        var key = Tokens.hash(pairingId);
        var pairing = pairings.get(key);
        if (pairing == null || !pairing.approved() || pairing.isExpired(System.currentTimeMillis(), PAIRING_LIFETIME_MILLIS)) return null;
        if (!pairings.remove(key, pairing)) return null;
        if (trustedDevices.size() >= MAX_TRUSTED_DEVICES) return null;
        var deviceToken = Tokens.newToken();
        trustedDevices.add(Tokens.hash(deviceToken));
        Log.LOGGER.info("A browser from {} was trusted for {}", pairing.address(), Names.sanitize(title()));
        return deviceToken;
    }

    public List<Pairing> pendingPairings() {
        prunePairings();
        var result = new ArrayList<Pairing>();
        for (var pairing : pairings.values()) {
            if (!pairing.approved()) result.add(pairing);
        }
        result.sort(Comparator.comparingLong(Pairing::created));
        return result;
    }

    public boolean approve(String code) {
        var normalized = Tokens.normalizePairingCode(code);
        if (normalized.length() != 8 || closed) return false;
        prunePairings();
        for (var pairing : pairings.values()) {
            if (!pairing.approved() && Tokens.normalizePairingCode(pairing.code()).equals(normalized)) {
                pairing.approve();
                return true;
            }
        }
        return false;
    }

    void attach(@Nullable ComputerHandle computer) {
        if (computer != null && computer.isCommandComputer() && !settings.allowCommandComputers()) computer = null;
        this.computer = computer;
        if (computer != null) label = computer.label();
    }

    void touch() {
        lastActive = System.currentTimeMillis();
    }

    boolean isExpired(long now, long idleMillis, long lifetimeMillis) {
        return now - created > lifetimeMillis || sockets.isEmpty() && now - lastActive > idleMillis;
    }

    void tick(int ticks) {
        resolve(ticks);

        var nextState = describeState();
        if (!nextState.equals(state)) {
            state = nextState;
            broadcast(nextState);
        }

        runControls();
        runInput();
        if (releaseRequested.getAndSet(false) && sockets.isEmpty()) releaseInputs();
        if (ticks % TERMINAL_INTERVAL_TICKS == 0 && sockets.stream().anyMatch(StudioSocket::wantsTerminal)) publishTerminal();
        runPending();
        sendPairingNotices();
        if (!sockets.isEmpty()) lastActive = System.currentTimeMillis();
    }

    void pollChanges() {
        if (System.currentTimeMillis() - fileSystem.lastMutation() < RESYNC_WINDOW_MILLIS) fileSystem.syncUsage();
        if (sockets.isEmpty()) return;
        var changes = fileSystem.pollChanges();
        if (changes.isEmpty()) return;

        var list = new JsonArray();
        for (var change : changes) {
            var item = new JsonObject();
            item.addProperty("type", change.type());
            item.addProperty("path", change.path());
            list.add(item);
        }
        var message = new JsonObject();
        message.addProperty("event", "changes");
        message.add("changes", list);
        broadcast(Json.write(message));
    }

    public boolean connect(StudioSocket socket) {
        if (closed || sockets.size() >= settings.maxConnectionsPerSession()) return false;
        sockets.add(socket);
        touch();
        socket.send(hello());
        socket.send(state);
        return true;
    }

    public void disconnect(StudioSocket socket) {
        if (!sockets.remove(socket)) return;
        touch();
        if (sockets.isEmpty()) releaseRequested.set(true);
    }

    void close(String reason) {
        if (closed) return;
        closed = true;
        trustedDevices.clear();
        pairings.clear();
        pairingNotices.clear();
        for (var socket : sockets) socket.closeSession(reason);
        sockets.clear();
        inputQueue.clear();
        releaseInputs();
        PendingControl control;
        while ((control = controlQueue.poll()) != null) control.done().accept(reason);
        if (fileSystem.lastMutation() != 0) fileSystem.syncUsage();
    }

    public boolean input(JsonArray events) {
        if (closed || events.isEmpty() || events.size() > MAX_EVENTS_PER_MESSAGE) return false;
        var cost = (double) events.size();
        for (var element : events) {
            if (element.isJsonArray() && element.getAsJsonArray().size() > 1 && element.getAsJsonArray().get(1).isJsonPrimitive()) {
                cost += Math.min(element.getAsJsonArray().get(1).getAsString().length(), MAX_PASTE_CHARACTERS) / 64.0;
            }
        }
        if (!inputCost.tryAcquire(cost)) return false;
        if (queuedInputEvents.addAndGet(events.size()) > MAX_QUEUED_INPUT_EVENTS) {
            queuedInputEvents.addAndGet(-events.size());
            return false;
        }
        inputQueue.add(events);
        return true;
    }

    public void control(String action, @Nullable String path, Consumer<@Nullable String> done) {
        if (closed) {
            done.accept("The editor session has ended.");
            return;
        }
        if (queuedControls.incrementAndGet() > MAX_QUEUED_CONTROLS) {
            queuedControls.decrementAndGet();
            done.accept("Too many pending actions, slow down.");
            return;
        }
        controlQueue.add(new PendingControl(action, path, done));
    }

    private void prunePairings() {
        var now = System.currentTimeMillis();
        pairings.values().removeIf(pairing -> pairing.isExpired(now, PAIRING_LIFETIME_MILLIS));
    }

    private void sendPairingNotices() {
        if (pairingNotices.isEmpty()) return;
        var now = System.currentTimeMillis();
        if (now - lastPairingNotice < PAIRING_NOTICE_INTERVAL_MILLIS) return;
        Pairing latest = null;
        Pairing next;
        while ((next = pairingNotices.poll()) != null) latest = next;
        var current = computer;
        if (latest == null || current == null) return;
        lastPairingNotice = now;
        platform.sendPairingNotice(current, "code trust " + latest.code(), latest.address(), latest.browser());
    }

    private void resolve(int ticks) {
        var current = platform.resolve(computerId, computer, computer != null || ticks % 20 == 0);
        if (current != computer) {
            input = null;
            inputOwner = null;
        }
        attach(current);
    }

    private String describeState() {
        var current = computer;
        var json = new JsonObject();
        json.addProperty("event", "state");
        json.addProperty("computer", computerId);
        if (label != null) json.addProperty("label", label);
        if (current == null) {
            json.addProperty("state", "unloaded");
        } else {
            json.addProperty("state", current.isOn() ? "on" : "off");
            json.addProperty("family", current.family());
        }
        return Json.write(json);
    }

    private String hello() {
        var json = new JsonObject();
        json.addProperty("event", "hello");
        json.addProperty("computer", computerId);
        json.addProperty("title", title());
        json.addProperty("version", platform.modVersion());
        json.addProperty("idleTimeout", settings.idleTimeoutMinutes());
        return Json.write(json);
    }

    private void publishTerminal() {
        var current = computer;
        var next = current == null ? TerminalFrames.unloaded() : TerminalFrames.encode(current.terminal());
        var changed = !next.equals(frame);
        frame = next;
        for (var socket : sockets) {
            if (socket.wantsTerminal() && (socket.consumeFrameRequest() || changed)) socket.sendFrame(next);
        }
    }

    private void broadcast(String message) {
        for (var socket : sockets) socket.send(message);
    }

    private ComputerInput input(ComputerHandle current) {
        if (input == null || inputOwner != current) {
            input = current.createInput();
            inputOwner = current;
        }
        return input;
    }

    private void releaseInputs() {
        if (input != null) input.releaseAll();
    }

    private void runControls() {
        PendingControl control;
        while ((control = controlQueue.poll()) != null) {
            queuedControls.decrementAndGet();
            try {
                control.done().accept(applyControl(control.action(), control.path()));
            } catch (FsException e) {
                control.done().accept(e.getMessage());
            } catch (RuntimeException e) {
                Log.LOGGER.warn("Failed to run '{}' on {}", control.action(), Names.sanitize(title()), e);
                control.done().accept("Internal error, see the server log.");
            }
        }
    }

    private void runInput() {
        var processed = 0;
        JsonArray events;
        while ((events = inputQueue.peek()) != null && (processed == 0 || processed + events.size() <= MAX_INPUT_EVENTS_PER_TICK)) {
            inputQueue.poll();
            queuedInputEvents.addAndGet(-events.size());
            processed += events.size();
            applyInput(events);
        }
    }

    private void applyInput(JsonArray events) {
        var current = computer;
        if (current == null || !current.isOn()) return;
        var target = input(current);
        for (JsonElement element : events) {
            if (!element.isJsonArray()) continue;
            var event = element.getAsJsonArray();
            if (event.isEmpty()) continue;
            try {
                applyInput(target, event);
            } catch (RuntimeException e) {
                Log.LOGGER.debug("Ignoring malformed input event", e);
            }
        }
    }

    private static void applyInput(ComputerInput target, JsonArray event) {
        switch (event.get(0).getAsString()) {
            case "key" -> {
                var key = event.get(1).getAsInt();
                if (key >= 0 && key <= 512) target.keyDown(key);
            }
            case "keyUp" -> {
                var key = event.get(1).getAsInt();
                if (key >= 0 && key <= 512) target.keyUp(key);
            }
            case "char" -> event.get(1).getAsString().codePoints().limit(MAX_CHAR_CODEPOINTS).forEach(target::codepointTyped);
            case "paste" -> {
                var text = event.get(1).getAsString();
                target.paste(text.length() > MAX_PASTE_CHARACTERS ? text.substring(0, MAX_PASTE_CHARACTERS) : text);
            }
            case "mouseClick" -> target.mouseClick(event.get(1).getAsInt(), event.get(2).getAsInt(), event.get(3).getAsInt());
            case "mouseUp" -> target.mouseUp(event.get(1).getAsInt(), event.get(2).getAsInt(), event.get(3).getAsInt());
            case "mouseDrag" -> target.mouseDrag(event.get(1).getAsInt(), event.get(2).getAsInt(), event.get(3).getAsInt());
            case "mouseScroll" -> target.mouseScroll(Integer.signum(event.get(1).getAsInt()), event.get(2).getAsInt(), event.get(3).getAsInt());
            default -> {
            }
        }
    }

    private @Nullable String applyControl(String action, @Nullable String path) throws FsException {
        var current = computer;
        if (current == null) return "The computer is not loaded. Make sure its chunk is loaded.";
        switch (action) {
            case "turnOn" -> current.turnOn();
            case "shutdown" -> current.shutdown();
            case "reboot" -> {
                if (current.isOn()) {
                    current.reboot();
                } else {
                    current.turnOn();
                }
            }
            case "terminate" -> {
                if (!current.isOn()) return "The computer is off.";
                current.queueEvent("terminate");
            }
            case "run" -> {
                if (path == null) return "No program to run.";
                var command = shellCommand(SessionFileSystem.normalize(path));
                if (current.isOn()) {
                    type(current, command);
                } else {
                    current.turnOn();
                    pendingRun = command;
                    pendingRunTicks = 0;
                }
            }
            default -> {
                return "Unknown action.";
            }
        }
        return null;
    }

    private void runPending() {
        if (pendingRun == null) return;
        var current = computer;
        pendingRunTicks++;
        if (current != null && current.isOn() && pendingRunTicks >= RUN_DELAY_TICKS) {
            type(current, pendingRun);
            pendingRun = null;
        } else if (pendingRunTicks > RUN_TIMEOUT_TICKS) {
            pendingRun = null;
        }
    }

    private void type(ComputerHandle current, String command) {
        var target = input(current);
        target.paste(command);
        target.keyDown(KEY_ENTER);
        target.keyUp(KEY_ENTER);
    }

    private static String shellCommand(String path) {
        var absolute = "/" + path;
        return absolute.indexOf(' ') >= 0 ? "\"" + absolute + "\"" : absolute;
    }

    public record NewPairing(String pairingId, Pairing pairing) {
    }

    private record PendingControl(String action, @Nullable String path, Consumer<@Nullable String> done) {
    }
}
