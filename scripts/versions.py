#!/usr/bin/env python3
import argparse
import json
import os
import re
import shutil
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLATFORMS = ROOT / "platforms"
BUILD_LOGIC_PROPERTIES = ROOT / "build-logic" / "gradle.properties"

NEOFORGE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
MODDEV = "https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/maven-metadata.xml"
CC_TWEAKED = "https://maven.squiddev.cc/cc/tweaked/cc-tweaked-{minecraft}-{loader}/maven-metadata.xml"
PARCHMENT = "https://maven.parchmentmc.org/org/parchmentmc/data/parchment-{minecraft}/maven-metadata.xml"
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
CC_TWEAKED_LOADERS = ("neoforge", "forge")


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "ccstudio-versions"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def maven_versions(url):
    data = fetch(url)
    return re.findall(r"<version>([^<]+)</version>", data.decode()) if data else []


def version_key(version):
    parts = re.split(r"[.\-+]", version)
    key = [(0, int(part)) if part.isdigit() else (-1, part) for part in parts]
    stable = 0 if re.search(r"(alpha|beta|rc|pre)", version) else 1
    return key, stable


def neoforge_prefix(minecraft):
    parts = minecraft.split(".")
    if parts[0] == "1":
        return f"{parts[1]}.{parts[2] if len(parts) > 2 else 0}."
    return ".".join((parts + ["0", "0"])[:3]) + "."


def next_prefix(prefix):
    parts = prefix.rstrip(".").split(".")
    parts[-1] = str(int(parts[-1]) + 1)
    return ".".join(parts)


def latest(versions):
    if not versions:
        return None
    stable = [version for version in versions if version_key(version)[1] == 1]
    return max(stable or versions, key=version_key)


def latest_neoforge(minecraft, all_versions=None):
    prefix = neoforge_prefix(minecraft)
    versions = [version for version in (all_versions or maven_versions(NEOFORGE)) if version.startswith(prefix)]
    return latest(versions)


def latest_cc_tweaked(minecraft):
    for loader in CC_TWEAKED_LOADERS:
        version = latest(maven_versions(CC_TWEAKED.format(minecraft=minecraft, loader=loader)))
        if version:
            return version, loader
    return None, None


def latest_parchment(minecraft):
    return latest(maven_versions(PARCHMENT.format(minecraft=minecraft)))


def minecraft_details(minecraft):
    manifest = json.loads(fetch(MANIFEST))
    entry = next((version for version in manifest["versions"] if version["id"] == minecraft), None)
    if entry is None:
        raise SystemExit(f"Minecraft {minecraft} does not exist")
    details = json.loads(fetch(entry["url"]))
    java = details.get("javaVersion", {}).get("majorVersion", 21)
    names = [library["name"] for library in details["libraries"]]
    netty = next((name.split(":")[2] for name in names if name.startswith("io.netty:netty-common:")), None)
    ships_http = any(name.startswith("io.netty:netty-codec-http:") for name in names)
    return java, netty, ships_http


def minecraft_releases():
    manifest = json.loads(fetch(MANIFEST))
    return [version["id"] for version in manifest["versions"] if version["type"] == "release"]


def read_properties(path):
    properties = {}
    for line in path.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    return properties


def write_properties(path, updates):
    lines = path.read_text().splitlines()
    seen = set()
    for index, line in enumerate(lines):
        key = line.split("=", 1)[0].strip()
        if "=" in line and key in updates:
            lines[index] = f"{key}={updates[key]}"
            seen.add(key)
    lines.extend(f"{key}={value}" for key, value in updates.items() if key not in seen)
    path.write_text("\n".join(lines) + "\n")


def platform_dirs():
    found = [directory for directory in PLATFORMS.iterdir() if (directory / "build.gradle").is_file()]
    return sorted(found, key=lambda directory: version_key(read_properties(directory / "gradle.properties")["minecraft_version"]))


def resolve(minecraft):
    neoforge = latest_neoforge(minecraft)
    cc_tweaked, loader = latest_cc_tweaked(minecraft)
    return neoforge, cc_tweaked, loader, latest_parchment(minecraft)


def command_new(arguments):
    minecraft = arguments.minecraft
    target = PLATFORMS / f"neoforge-{minecraft}"
    if target.exists():
        raise SystemExit(f"{target.relative_to(ROOT)} already exists")
    existing = platform_dirs()
    source = PLATFORMS / arguments.source if arguments.source else existing[-1]
    if not (source / "build.gradle").is_file():
        raise SystemExit(f"{source.relative_to(ROOT)} is not a platform")

    neoforge, cc_tweaked, loader, parchment = resolve(minecraft)
    if neoforge is None:
        raise SystemExit(f"NeoForge has no release for Minecraft {minecraft} yet")
    if cc_tweaked is None:
        raise SystemExit(f"CC: Tweaked has no release for Minecraft {minecraft} yet")
    java, netty, ships_http = minecraft_details(minecraft)

    shutil.copytree(source, target, ignore=shutil.ignore_patterns("run", "build", ".gradle"))
    prefix = neoforge_prefix(minecraft)
    write_properties(target / "gradle.properties", {
        "minecraft_version": minecraft,
        "minecraft_version_range": f"[{minecraft}]",
        "java_version": java,
        "neo_version": neoforge,
        "neo_version_range": f"[{neoforge},{next_prefix(prefix)})",
        "parchment_minecraft_version": minecraft if parchment else "",
        "parchment_mappings_version": parchment or "",
        "cc_tweaked_version": cc_tweaked,
        "cc_tweaked_version_range": f"[{cc_tweaked},)",
        "cc_tweaked_loader": loader,
        "netty_version": netty or read_properties(source / "gradle.properties")["netty_version"],
        "bundle_netty_http": "false" if ships_http else "true",
    })
    print(f"Created platforms/{target.name} from platforms/{source.name}")
    print(f"  NeoForge {neoforge}, CC: Tweaked {cc_tweaked} ({loader}), Java {java}, Netty {netty} ({'provided by Minecraft' if ships_http else 'bundled'}), Parchment {parchment or 'none'}")
    print(f"Next: ./gradlew :{target.name}:build and fix any compile errors in platforms/{target.name}/src")


def command_update(arguments):
    selected = [PLATFORMS / name for name in arguments.platforms] if arguments.platforms else platform_dirs()
    all_neoforge = maven_versions(NEOFORGE)
    changed = False
    for directory in selected:
        path = directory / "gradle.properties"
        properties = read_properties(path)
        minecraft = properties["minecraft_version"]
        updates = {}
        neoforge = latest_neoforge(minecraft, all_neoforge)
        if neoforge and version_key(neoforge) > version_key(properties["neo_version"]):
            updates["neo_version"] = neoforge
            updates["neo_version_range"] = f"[{neoforge},{next_prefix(neoforge_prefix(minecraft))})"
        cc_tweaked, _ = latest_cc_tweaked(minecraft)
        if cc_tweaked and version_key(cc_tweaked) > version_key(properties["cc_tweaked_version"]):
            updates["cc_tweaked_version"] = cc_tweaked
        parchment = latest_parchment(properties.get("parchment_minecraft_version") or minecraft)
        if parchment and properties.get("parchment_mappings_version") and version_key(parchment) > version_key(properties["parchment_mappings_version"]):
            updates["parchment_mappings_version"] = parchment
        if updates:
            write_properties(path, updates)
            changed = True
            print(f"{directory.name}: " + ", ".join(f"{key} {properties.get(key)} -> {value}" for key, value in updates.items()))
        else:
            print(f"{directory.name}: up to date")

    moddev = latest(maven_versions(MODDEV))
    current = read_properties(BUILD_LOGIC_PROPERTIES)["moddev_version"]
    if moddev and version_key(moddev) > version_key(current) and moddev.split(".")[0] == current.split(".")[0]:
        write_properties(BUILD_LOGIC_PROPERTIES, {"moddev_version": moddev})
        changed = True
        print(f"build-logic: moddev_version {current} -> {moddev}")
    if not changed:
        print("Everything is up to date")


def command_check(arguments):
    existing = platform_dirs()
    supported = {read_properties(directory / "gradle.properties")["minecraft_version"] for directory in existing}
    newest = max(supported, key=version_key)
    all_neoforge = maven_versions(NEOFORGE)
    candidates = []
    for minecraft in minecraft_releases():
        if minecraft in supported or version_key(minecraft) <= version_key(newest):
            continue
        if latest_neoforge(minecraft, all_neoforge) and latest_cc_tweaked(minecraft)[0]:
            candidates.append(minecraft)
    candidates.sort(key=version_key)
    if arguments.json:
        print(json.dumps(candidates))
        return
    print("Supported: " + ", ".join(sorted(supported, key=version_key)))
    if candidates:
        print("Can be added (NeoForge and CC: Tweaked are available): " + ", ".join(candidates))
        print("Add one with: python3 scripts/versions.py new <version>")
    else:
        print("No newer Minecraft version has both NeoForge and CC: Tweaked releases yet")


def main():
    parser = argparse.ArgumentParser(description="Manage the Minecraft versions CC: Studio supports.")
    commands = parser.add_subparsers(dest="command", required=True)

    new = commands.add_parser("new", help="scaffold a platform for a new Minecraft version")
    new.add_argument("minecraft", help="Minecraft version, e.g. 1.21.4 or 26.3")
    new.add_argument("--from", dest="source", help="platform folder to copy (default: the newest one)")
    new.set_defaults(handler=command_new)

    update = commands.add_parser("update", help="bump NeoForge, CC: Tweaked, Parchment and ModDevGradle to their latest patch releases")
    update.add_argument("platforms", nargs="*", help="platform folders to update (default: all)")
    update.set_defaults(handler=command_update)

    check = commands.add_parser("check", help="list newer Minecraft versions that can be supported")
    check.add_argument("--json", action="store_true", help="print a JSON array of versions")
    check.set_defaults(handler=command_check)

    arguments = parser.parse_args()
    os.chdir(ROOT)
    arguments.handler(arguments)


if __name__ == "__main__":
    sys.exit(main())
