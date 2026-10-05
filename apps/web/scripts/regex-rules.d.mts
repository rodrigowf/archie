export declare const UNSAFE_FLAGS: string[];
export interface RegexIssue {
  feature: string;
  message: string;
}
export declare function findUnsafeRegexFeatures(pattern: string, flags?: string): RegexIssue[];
export declare function findSuspiciousRegexText(text: string): string | null;
