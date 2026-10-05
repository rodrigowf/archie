/**
 * The markdown corpus (spec 13 §6.1): `*.md` files in this folder plus one fence per core
 * language. Used by the oracle test, the compat render test and the QA page.
 */
import { languageSamples } from './languageSamples';

const files = import.meta.glob<string>('./*.md', { query: '?raw', import: 'default', eager: true });

/** `{ 'tables.md': '…', … }` */
export const corpusFiles: Record<string, string> = Object.fromEntries(
  Object.entries(files).map(([path, text]) => [path.replace(/^\.\//, ''), text]),
);

/** Every core language as a fenced block, in one document. */
export const languagesDoc: string = Object.entries(languageSamples)
  .map(([lang, code]) => `### ${lang}\n\n\`\`\`${lang}\n${code}\n\`\`\``)
  .join('\n\n');

/** All corpus documents, including the languages document. */
export const corpus: Record<string, string> = { ...corpusFiles, 'languages.md': languagesDoc };
