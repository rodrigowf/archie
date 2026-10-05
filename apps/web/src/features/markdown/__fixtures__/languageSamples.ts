/** One short sample per core language (spec 13 §2.4), used by the corpus and the compat tests. */
export const languageSamples: Record<string, string> = {
  bash: 'for f in *.md; do\n  echo "file: $f" # comment\ndone',
  shell: '$ ls -la ~/assistant\ntotal 42',
  javascript: "const add = (a, b) => a + b; // sum\nconsole.log(`x=${add(1, 2)}`);",
  typescript: 'interface User { id: string; age?: number }\nexport const u: User = { id: "a" };',
  json: '{ "name": "archie", "version": 1, "ok": true, "tags": [null] }',
  python: 'import os\n\n@dataclass\nclass A:\n    """Doc."""\n    x: int = 0  # comment',
  kotlin: 'data class User(val id: String)\nfun main() = println("hi ${User("a")}")',
  java: 'public class Main {\n  public static void main(String[] args) { System.out.println("hi"); }\n}',
  css: '.root > * + * { margin-top: 8px; color: #fff; }\n@media (min-width: 600px) { a { color: red } }',
  scss: '$space: 8px;\n.a { &:hover { margin: $space * 2; } }',
  xml: '<!DOCTYPE html>\n<html lang="en"><body class="x"><p>Hi &amp; bye</p></body></html>',
  yaml: 'name: archie\nitems:\n  - one # comment\n  - "two"\nenabled: true',
  markdown: '# Title\n\n- **bold** item\n\n~~~js\nx\n~~~',
  diff: '--- a/file\n+++ b/file\n@@ -1,2 +1,2 @@\n-old line\n+new line',
  sql: "SELECT id, name FROM users WHERE age > 21 AND name LIKE 'R%' ORDER BY id;",
  go: 'package main\n\nimport "fmt"\n\nfunc main() { fmt.Println("hi") }',
  rust: 'fn main() {\n    let v: Vec<u8> = vec![1, 2];\n    println!("{:?}", v);\n}',
  c: '#include <stdio.h>\nint main(void) { printf("%d\\n", 42); return 0; }',
  cpp: '#include <vector>\ntemplate <typename T> T id(T x) { return x; }\nauto v = std::vector<int>{1};',
  csharp: 'using System;\npublic record User(string Id);\nvar u = new User("a"); Console.WriteLine($"{u}");',
  dockerfile: 'FROM node:22-alpine\nWORKDIR /app\nCOPY . .\nRUN npm ci\nCMD ["node", "server.js"]',
  ini: '[server]\nhost = "0.0.0.0" ; comment\nport = 8765',
  makefile: 'build: deps\n\t$(CC) -o app main.c\n\n.PHONY: build',
  nginx: 'server {\n  listen 443 ssl;\n  location /api { proxy_pass http://127.0.0.1:8765; }\n}',
  php: '<?php\nfunction greet(string $n): string { return "Hi $n"; }\necho greet("a");',
  ruby: 'class User\n  attr_reader :id\n  def initialize(id) = @id = id\nend\nputs "hi #{1 + 1}"',
  lua: 'local function add(a, b)\n  return a + b -- sum\nend\nprint(add(1, 2))',
  powershell: 'Get-ChildItem -Path . -Filter *.md | ForEach-Object { Write-Host $_.Name }',
  plaintext: 'just text, nothing to highlight',
};

/** A long code block: `lines` numbered lines of Python. */
export function longCode(lines: number): string {
  const out: string[] = [];
  for (let i = 1; i <= lines; i++) out.push(`value_${i} = compute(${i}, "line ${i}")  # step ${i}`);
  return out.join('\n');
}
