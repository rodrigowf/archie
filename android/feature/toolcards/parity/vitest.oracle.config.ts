/** Vitest config for web-oracle.test.tsx; run through run-web-oracle.sh (needs REPO_ROOT). */
import path from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig(async () => {
  const repo = process.env.REPO_ROOT as string;
  const fn = path.join(repo, 'frontend');
  const shared = await import(path.join(fn, 'vite.shared.ts'));
  return {
    plugins: [react()],
    resolve: { alias: shared.targetAliases('main') },
    define: shared.targetDefine('main'),
    server: { fs: { allow: [repo, process.cwd()] } },
    test: {
      globals: true,
      environment: 'jsdom',
      include: ['web-oracle.test.tsx'],
      setupFiles: [path.join(fn, 'src/test/setup.ts')],
      server: { deps: { inline: ['remark-gfm', 'mdast-util-gfm', 'micromark-extension-gfm'] } },
    },
  };
});
