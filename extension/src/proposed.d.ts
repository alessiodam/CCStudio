declare module 'vscode' {
  export interface FileSearchQuery {
    pattern: string;
  }

  export type GlobString = string;

  export interface SearchOptions {
    folder: Uri;
    includes: GlobString[];
    excludes: GlobString[];
    useIgnoreFiles: boolean;
    followSymlinks: boolean;
    useGlobalIgnoreFiles: boolean;
    useParentIgnoreFiles: boolean;
  }

  export interface FileSearchOptions extends SearchOptions {
    maxResults?: number;
    session?: CancellationToken;
  }

  export interface FileSearchProvider {
    provideFileSearchResults(query: FileSearchQuery, options: FileSearchOptions, token: CancellationToken): ProviderResult<Uri[]>;
  }

  export interface TextSearchQuery {
    pattern: string;
    isMultiline?: boolean;
    isRegExp?: boolean;
    isCaseSensitive?: boolean;
    isWordMatch?: boolean;
  }

  export interface TextSearchPreviewOptions {
    matchLines: number;
    charsPerLine: number;
  }

  export interface TextSearchOptions extends SearchOptions {
    maxResults: number;
    previewOptions?: TextSearchPreviewOptions;
    maxFileSize?: number;
    encoding?: string;
    beforeContext?: number;
    afterContext?: number;
  }

  export interface TextSearchComplete {
    limitHit?: boolean;
  }

  export interface TextSearchMatchPreview {
    text: string;
    matches: Range | Range[];
  }

  export interface TextSearchMatch {
    uri: Uri;
    ranges: Range | Range[];
    preview: TextSearchMatchPreview;
  }

  export interface TextSearchContext {
    uri: Uri;
    text: string;
    lineNumber: number;
  }

  export type TextSearchResult = TextSearchMatch | TextSearchContext;

  export interface TextSearchProvider {
    provideTextSearchResults(query: TextSearchQuery, options: TextSearchOptions, progress: Progress<TextSearchResult>, token: CancellationToken): ProviderResult<TextSearchComplete>;
  }

  export namespace workspace {
    export function registerFileSearchProvider(scheme: string, provider: FileSearchProvider): Disposable;
    export function registerTextSearchProvider(scheme: string, provider: TextSearchProvider): Disposable;
  }
}
