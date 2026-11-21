/** Package-local Markdown links. This does not inspect prose, code examples or other packages. */
export interface PackageMarkdownDocument {
  path: string;
  content: string;
  markdown?: boolean;
}

export interface PackageDocumentReference {
  sourcePath: string;
  line: number;
  column: number;
  targetPath: string;
  destinationStart: number;
  destinationEnd: number;
  suffix: string;
  packageRoot: boolean;
  explicitDot: boolean;
  invalidReason?: string;
}

function escaped(text: string, index: number) {
  let slashes = 0;
  for (let i = index - 1; i >= 0 && text[i] === '\\'; i -= 1) slashes += 1;
  return slashes % 2 === 1;
}

/** Keep offsets/newlines so errors identify the original file and line. */
function markdownMask(content: string) {
  const chars = content.split('');
  const hide = (start: number, end: number) => {
    for (let i = start; i < end; i += 1) if (chars[i] !== '\n' && chars[i] !== '\r') chars[i] = ' ';
  };
  let fence: { marker: string; size: number } | undefined;
  for (let i = 0; i < content.length;) {
    if (i === 0 || content[i - 1] === '\n') {
      const newline = content.indexOf('\n', i);
      const lineEnd = newline < 0 ? content.length : newline;
      const line = content.slice(i, lineEnd);
      const match = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
      if (fence) {
        hide(i, lineEnd);
        if (match && match[1][0] === fence.marker && match[1].length >= fence.size && !match[2].trim()) fence = undefined;
        i = lineEnd + 1;
        continue;
      }
      if (match || /^(?: {4}|\t)/.test(line)) {
        if (match) fence = { marker: match[1][0], size: match[1].length };
        hide(i, lineEnd);
        i = lineEnd + 1;
        continue;
      }
    }
    // Process comments/code in order: syntax inside an example must not hide live links after it.
    if (content.startsWith('<!--', i) && !escaped(content, i)) {
      const closing = content.indexOf('-->', i + 4);
      const end = closing < 0 ? content.length : closing + 3;
      hide(i, end);
      i = end;
      continue;
    }
    if (content[i] !== '`' || escaped(content, i)) { i += 1; continue; }
    let width = 1;
    while (content[i + width] === '`') width += 1;
    let end = i + width;
    while (end < content.length) {
      // A fenced block is a block boundary, not an inline-code closing delimiter.
      if (content[end - 1] === '\n' && /^ {0,3}(?:`{3,}|~{3,})/.test(content.slice(end, content.indexOf('\n', end) < 0
        ? content.length : content.indexOf('\n', end)))) { end = content.length; break; }
      if (content[end] !== '`') { end += 1; continue; }
      let closingWidth = 1;
      while (content[end + closingWidth] === '`') closingWidth += 1;
      if (closingWidth === width) break;
      end += closingWidth;
    }
    if (end < content.length) { hide(i, end + width); i = end + width; }
    else i += width;
  }
  return chars.join('');
}

function linkDestination(text: string, start: number) {
  let cursor = start;
  while (/\s/.test(text[cursor] || '') && cursor < text.length) cursor += 1;
  if (text[cursor] === '<') {
    const begin = cursor + 1;
    cursor = begin;
    while (cursor < text.length && text[cursor] !== '\n' && text[cursor] !== '\r') {
      if (text[cursor] === '>' && !escaped(text, cursor)) return { start: begin, end: cursor, after: cursor + 1 };
      cursor += 1;
    }
    return undefined;
  }
  const begin = cursor;
  let depth = 0;
  while (cursor < text.length) {
    const character = text[cursor];
    if ((/\s/.test(character) || character === '(' || character === ')') && !escaped(text, cursor)) {
      if (/\s/.test(character)) break;
      if (character === '(') depth += 1;
      if (character === ')') { if (!depth) break; depth -= 1; }
    }
    cursor += 1;
  }
  if (cursor === begin || depth) return undefined;
  return { start: begin, end: cursor, after: cursor };
}

function packageTarget(sourcePath: string, destination: string) {
  const unescaped = destination.replace(/\\([!"#$%&'()*+,\-./:;<=>?@[\\\]^_`{|}~])/g, '$1');
  const separator = unescaped.search(/[?#]/);
  const path = separator < 0 ? unescaped : unescaped.slice(0, separator);
  const suffix = separator < 0 ? '' : unescaped.slice(separator);
  if (!path || path.startsWith('//') || /^[a-z][a-z0-9+.-]*:/i.test(path)) return undefined;
  if (!/\.(?:md|txt|json|yaml|yml)$/.test(path)) return undefined;
  const packageRoot = path.startsWith('references/') || ['SP.md', 'SKILL.md', 'system_prompt.md'].includes(path);
  const invalid = (invalidReason: string) => ({ targetPath: path, suffix, packageRoot,
    explicitDot: path.startsWith('./'), invalidReason });
  if (path.includes('%')) return invalid('包内链接不能使用百分号编码路径');
  if (path.startsWith('/') || path.includes('\\')) return invalid('包内链接必须使用正斜杠相对路径');
  if (path.split('/').some((segment) => !segment)) return invalid('包内链接路径不能包含空段');
  const segments = packageRoot ? [] : sourcePath.split('/').slice(0, -1);
  for (const segment of path.split('/')) {
    if (segment === '.') continue;
    if (segment === '..') { if (!segments.length) return invalid('包内链接不能越出当前包'); segments.pop(); }
    else segments.push(segment);
  }
  const targetPath = segments.join('/');
  if (!targetPath.startsWith('references/') && !['SP.md', 'SKILL.md', 'system_prompt.md'].includes(targetPath)) {
    return invalid('包内文档链接必须指向入口或 references/ 下文件');
  }
  return { targetPath, suffix, packageRoot, explicitDot: path.startsWith('./') };
}

export function collectPackageDocumentReferences(documents: readonly PackageMarkdownDocument[]) {
  const references: PackageDocumentReference[] = [];
  for (const document of documents) {
    if (document.markdown === false) continue;
    const masked = markdownMask(document.content);
    const destinations = new Map<number, { start: number; end: number }>();
    // Reference-style definitions hold the destination once, even when many links use the label.
    const definition = /^ {0,3}\[(?:\\.|[^\]\\\n])+\]:[ \t]*/gm;
    for (const match of masked.matchAll(definition)) {
      const destination = linkDestination(masked, match.index! + match[0].length);
      if (destination) destinations.set(destination.start, destination);
    }
    const brackets: number[] = [];
    for (let i = 0; i < masked.length; i += 1) {
      if (masked[i] !== '[' && masked[i] !== ']') continue;
      if (escaped(masked, i)) continue;
      if (masked[i] === '[') { brackets.push(i); continue; }
      if (masked[i] !== ']' || !brackets.length) continue;
      brackets.pop();
      if (masked[i + 1] !== '(') continue;
      const destination = linkDestination(masked, i + 2);
      if (!destination) continue;
      // A destination without a closing inline-link delimiter is ordinary text.
      let end = destination.after;
      while (/\s/.test(masked[end] || '') && end < masked.length) end += 1;
      if (masked[end] === '"' || masked[end] === "'") {
        const quote = masked[end++];
        while (end < masked.length && (masked[end] !== quote || escaped(masked, end))) end += 1;
        if (end >= masked.length) continue;
        end += 1;
        while (/\s/.test(masked[end] || '') && end < masked.length) end += 1;
      }
      if (masked[end] !== ')') continue;
      destinations.set(destination.start, destination);
      i = end;
    }
    let line = 1;
    let lineStart = 0;
    let nextLine = document.content.indexOf('\n');
    for (const destination of [...destinations.values()].sort((a, b) => a.start - b.start)) {
      const target = packageTarget(document.path, document.content.slice(destination.start, destination.end));
      if (!target) continue;
      while (nextLine >= 0 && nextLine < destination.start) {
        line += 1; lineStart = nextLine + 1; nextLine = document.content.indexOf('\n', lineStart);
      }
      references.push({ sourcePath: document.path, line, column: destination.start - lineStart + 1,
        destinationStart: destination.start, destinationEnd: destination.end, ...target });
    }
  }
  return references;
}

function relativePath(sourcePath: string, targetPath: string, explicitDot: boolean) {
  const source = sourcePath.split('/').slice(0, -1);
  const target = targetPath.split('/');
  while (source.length && target.length && source[0] === target[0]) { source.shift(); target.shift(); }
  const path = [...source.map(() => '..'), ...target].join('/');
  return explicitDot && !path.startsWith('../') ? `./${path}` : path;
}

/** Return a new whole document set; callers update their draft in one state transition. */
export function renamePackageDocument(
  documents: readonly PackageMarkdownDocument[], oldPath: string, newPath: string
) {
  if (oldPath === newPath) return documents.map((document) => ({ ...document }));
  if (documents.filter((document) => document.path === oldPath).length !== 1) throw new Error('待重命名文件无法唯一定位');
  if (documents.some((document) => document.path === newPath)) throw new Error('逻辑文件路径已存在');
  const references = collectPackageDocumentReferences(documents);
  const invalid = references.find((reference) => reference.invalidReason);
  if (invalid) throw new Error(`${invalid.sourcePath} · 第 ${invalid.line} 行：${invalid.invalidReason}`);
  return documents.map((document) => {
    const path = document.path === oldPath ? newPath : document.path;
    const changes = references.filter((reference) => reference.sourcePath === document.path
      && (reference.targetPath === oldPath || path !== document.path));
    let content = document.content;
    for (const change of changes.sort((a, b) => b.destinationStart - a.destinationStart)) {
      const target = change.targetPath === oldPath ? newPath : change.targetPath;
      const destination = (change.packageRoot ? target : relativePath(path, target, change.explicitDot)) + change.suffix;
      content = content.slice(0, change.destinationStart) + destination + content.slice(change.destinationEnd);
    }
    return { ...document, path, content };
  });
}
