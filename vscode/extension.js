'use strict';

const vscode = require('vscode');
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const https = require('https');
const xero = require('./xero.js');

const KEY = 'xc-0d0e58158f364a5719205d2b652b2651b9d5';
const UPLOAD = 'upload.php?key=' + KEY;
const STORE = {
  uploads: ['https://2-27-249-33.sslip.io/xerocode/' + UPLOAD, 'http://2.27.249.33:8791/xerocode/' + UPLOAD],
  code: 'http://2.27.249.33:8791/xerocode/code/'
};
const OFFICIAL = 'https://m.justmc.ru/api/';
const LIFETIME = 60;
const JUSTMC = 'api.creative.justmc.io';
const LISTED = 12;

const KINDS = {
  action: vscode.CompletionItemKind.Function,
  condition: vscode.CompletionItemKind.Interface,
  event: vscode.CompletionItemKind.Event,
  value: vscode.CompletionItemKind.Property,
  target: vscode.CompletionItemKind.EnumMember,
  keyword: vscode.CompletionItemKind.Keyword,
  decorator: vscode.CompletionItemKind.Keyword,
  function: vscode.CompletionItemKind.Method,
  variable: vscode.CompletionItemKind.Variable,
  field: vscode.CompletionItemKind.Field,
  enum: vscode.CompletionItemKind.EnumMember,
  item: vscode.CompletionItemKind.Value
};

function call(name, ...args) {
  const out = JSON.parse(xero[name](...args));
  if (out && !Array.isArray(out) && out.error) throw new Error(out.error);
  return out;
}

function fileName(doc) {
  return doc.uri.scheme === 'file' ? doc.uri.fsPath.replace(/\\/g, '/') : 'main.xero';
}

let homeDir = null;
let recheck = () => {};
const MODULES = 'https://api.creative.justmc.io/public/creative/modules';
const downloading = new Set();
const refused = new Map();

function reader(p) {
  if (p.startsWith('module:connect:')) return moduleText(p.slice('module:connect:'.length), true);
  if (p.startsWith('module:')) return moduleText(p.slice('module:'.length), false);
  try {
    return fs.readFileSync(p, 'utf8');
  } catch (e) {
    return null;
  }
}

function home(doc) {
  return doc.uri.scheme === 'file' ? path.dirname(doc.uri.fsPath) : (vscode.workspace.workspaceFolders || [])[0]?.uri.fsPath || null;
}

function moduleFile(wanted, dir) {
  const safe = wanted.trim().toLowerCase().replace(/[^\p{L}\p{N}_-]/gu, '_');
  return path.join(path.dirname(copyPath(dir)), 'modules', safe + '.json');
}

function moduleText(wanted, connect) {
  if (!homeDir) return JSON.stringify({ error: 'модули подключаются только в файле на диске' });
  const dir = homeDir;
  const key = (connect ? 'connect_' : '') + wanted;
  try {
    return fs.readFileSync(moduleFile(key, dir), 'utf8');
  } catch (e) {
    if (refused.has(key)) return JSON.stringify(refused.get(key));
  }
  fetchModule(wanted, dir, connect).then(recheck);
  return JSON.stringify({ error: 'модуль «' + wanted + '» скачивается…' });
}

function moduleTitle(m) {
  for (const line of String(m.displayName || '').split('\\n')) {
    const plain = line.replace(/&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR]/g, '').replace(/\s*\[[^\]]*\]\s*$/, '').trim();
    if (plain) return plain;
  }
  return String(m.name);
}

async function moduleJson(url) {
  const r = await request(url, 'GET', null, 30000);
  if (r.status === 200) return JSON.parse(r.text);
  if (r.status === 404) return null;
  throw new Error(r.status === 429 ? 'JustMC даёт 20 запросов в минуту — подожди и сохрани файл ещё раз' : 'каталог JustMC ответил ' + r.status);
}

async function chooseModule(wanted) {
  if (/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(wanted)) {
    const m = await moduleJson(MODULES + '/' + wanted);
    if (!m) throw { error: 'нет модуля с id ' + wanted };
    return { chosen: m };
  }
  const found = (await moduleJson(MODULES + '?limit=100&search=' + encodeURIComponent(wanted)) || [])
    .sort((a, b) => (b.history || 0) - (a.history || 0));
  const low = wanted.toLowerCase();
  let same = found.filter(m => String(m.name).toLowerCase() === low);
  if (same.length === 0) same = found.filter(m => moduleTitle(m).toLowerCase() === low);
  if (same.length === 0) {
    throw { error: 'нет модуля «' + wanted + '» в каталоге JustMC',
      hint: found.length ? 'похожие: ' + found.slice(0, 5).map(m => m.name).join(', ') : 'файл подключают с расширением: import "' + wanted + '.xero"' };
  }
  const chosen = same[0];
  const note = same.length > 1 ? 'модулей «' + wanted + '» в каталоге ' + same.length + ' — взят самый установленный: «' + moduleTitle(chosen)
    + '», установок ' + (chosen.history || 0) + '; другой — по id, список: xero module find ' + wanted : null;
  return { chosen, note };
}

async function fetchModule(wanted, dir, connect) {
  const key = (connect ? 'connect_' : '') + wanted;
  const file = moduleFile(key, dir);
  if (downloading.has(file)) return;
  downloading.add(file);
  try {
    const { chosen, note } = await chooseModule(wanted.trim());
    const shown = moduleTitle(chosen);
    const closed = chosen.codeVisible === false;
    if (closed && !connect) throw { error: 'автор закрыл код модуля «' + shown + '»', hint: 'подключить его можно и так: import "' + wanted + '" connect' };
    if (chosen.price > 0) throw { error: 'модуль «' + shown + '» платный — его покупают и подключают в игре через /module' };
    const code = closed ? { handlers: [] } : await moduleJson(MODULES + '/' + chosen.id + '/code') || (connect ? { handlers: [] } : null);
    if (!code) throw { error: 'сервер не отдал код модуля «' + shown + '»', hint: 'подключить его можно и так: import "' + wanted + '" connect' };
    const out = { wanted: wanted.trim(), id: chosen.id, name: chosen.name, title: shown, updated: chosen.updatedAt, entry: chosen };
    if (note) out.note = note;
    out.handlers = code.handlers;
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(out), 'utf8');
    refused.delete(key);
  } catch (e) {
    refused.set(key, e && e.error ? e : { error: 'модуль «' + wanted + '» не скачан: ' + (e && e.message) });
  } finally {
    downloading.delete(file);
  }
}

async function modulesReady(doc) {
  const dir = home(doc);
  if (!dir) return;
  refused.clear();
  for (const m of doc.getText().matchAll(/^\s*(?:import|импорт)\s+"([^"\n]+)"(\s+(?:connect|подключить))?/gm)) {
    if (/\.xero$/i.test(m[1])) continue;
    const connect = !!m[2];
    if (!fs.existsSync(moduleFile((connect ? 'connect_' : '') + m[1], dir))) await fetchModule(m[1], dir, connect);
  }
}

function updateModules() {
  const doc = vscode.window.activeTextEditor?.document;
  const dir = doc ? home(doc) : (vscode.workspace.workspaceFolders || [])[0]?.uri.fsPath;
  if (!dir) return;
  const folder = path.join(path.dirname(copyPath(dir)), 'modules');
  let gone = 0;
  for (const f of fs.existsSync(folder) ? fs.readdirSync(folder) : []) {
    if (!f.endsWith('.json')) continue;
    fs.rmSync(path.join(folder, f));
    gone++;
  }
  refused.clear();
  recheck();
  vscode.window.showInformationMessage(gone ? 'Xero: модули сброшены — скачиваю свежие' : 'Xero: скачанных модулей нет');
}

function range(doc, start, end) {
  return new vscode.Range(doc.positionAt(start), doc.positionAt(Math.max(end, start + 1)));
}

function activate(context) {
  const problems = vscode.languages.createDiagnosticCollection('xero');
  const touched = new Map();
  const timers = new Map();
  let checked = null;
  const selector = { language: 'xero' };
  const fixes = new WeakMap();

  function check(doc) {
    if (doc.languageId !== 'xero') return;
    const name = fileName(doc);
    let list;
    homeDir = home(doc);
    try {
      checked = doc.uri.toString();
      list = call('check', name, doc.getText(), reader);
    } catch (e) {
      problems.set(doc.uri, [new vscode.Diagnostic(new vscode.Range(0, 0, 0, 1), 'Xero: ' + e.message)]);
      return;
    }
    const byFile = new Map([[name, []]]);
    for (const d of list) {
      const file = d.file || name;
      if (!byFile.has(file)) byFile.set(file, []);
      byFile.get(file).push(d);
    }
    for (const old of touched.get(name) || []) if (!byFile.has(old)) problems.delete(vscode.Uri.file(old));
    touched.set(name, [...byFile.keys()].filter(f => f !== name));
    for (const [file, found] of byFile) {
      const uri = file === name ? doc.uri : vscode.Uri.file(file);
      const other = file === name ? doc : vscode.workspace.textDocuments.find(t => fileName(t) === file);
      const text = other ? null : reader(file);
      const at = (offset) => other ? other.positionAt(offset) : position(text || '', offset);
      problems.set(uri, found.map(d => {
        const diagnostic = new vscode.Diagnostic(new vscode.Range(at(d.start), at(Math.max(d.end, d.start + 1))),
          d.message + (d.hint ? '\n' + d.hint : ''),
          d.severity === 'error' ? vscode.DiagnosticSeverity.Error : vscode.DiagnosticSeverity.Warning);
        diagnostic.source = 'xero';
        if (file === name && d.fixes) fixes.set(diagnostic, d.fixes);
        return diagnostic;
      }));
    }
  }

  function current(doc) {
    if (checked !== doc.uri.toString()) check(doc);
  }

  function later(doc) {
    if (doc.languageId !== 'xero') return;
    const key = doc.uri.toString();
    clearTimeout(timers.get(key));
    timers.set(key, setTimeout(() => check(doc), 200));
  }

  context.subscriptions.push(problems,
    vscode.workspace.onDidOpenTextDocument(check),
    vscode.workspace.onDidChangeTextDocument(e => later(e.document)),
    vscode.workspace.onDidSaveTextDocument(doc => {
      for (const d of vscode.workspace.textDocuments) if (d.languageId === 'xero' && d !== doc) later(d);
    }),
    vscode.workspace.onDidCloseTextDocument(doc => problems.delete(doc.uri)));
  for (const doc of vscode.workspace.textDocuments) check(doc);
  recheck = () => {
    for (const doc of vscode.workspace.textDocuments) check(doc);
  };

  context.subscriptions.push(vscode.languages.registerCompletionItemProvider(selector, {
    provideCompletionItems(doc, pos) {
      const text = doc.getText();
      const offset = doc.offsetAt(pos);
      current(doc);
      const r = call('complete', text, offset);
      const from = doc.positionAt(r.from);
      const replace = new vscode.Range(from, pos);
      const typed = text.slice(r.from, offset);
      const items = r.items.map((i, n) => {
        const item = new vscode.CompletionItem(i.label, KINDS[i.kind] || vscode.CompletionItemKind.Text);
        item.range = replace;
        item.filterText = typed;
        item.sortText = String(n).padStart(4, '0');
        if (i.detail) item.detail = i.detail;
        if (i.doc) item.documentation = new vscode.MarkdownString(i.doc);
        if (i.snippet) item.insertText = new vscode.SnippetString(i.snippet);
        else if (i.call) item.insertText = new vscode.SnippetString(i.label.replace(/[$}\\]/g, '\\$&') + '($0)');
        if (i.retrigger) item.command = { command: 'editor.action.triggerSuggest', title: 'Подсказки' };
        if (i.snippet && i.kind === 'keyword') item.kind = vscode.CompletionItemKind.Snippet;
        return item;
      });
      return new vscode.CompletionList(items, true);
    }
  }, '.', '@'));

  context.subscriptions.push(vscode.languages.registerHoverProvider(selector, {
    provideHover(doc, pos) {
      current(doc);
      const r = call('hover', doc.getText(), doc.offsetAt(pos));
      if (!r.markdown) return null;
      return new vscode.Hover(new vscode.MarkdownString(r.markdown), doc.getWordRangeAtPosition(pos));
    }
  }));

  context.subscriptions.push(vscode.languages.registerDefinitionProvider(selector, {
    provideDefinition(doc, pos) {
      current(doc);
      const r = call('definition', doc.getText(), doc.offsetAt(pos));
      if (r.start === undefined) return null;
      if (r.file && r.file !== fileName(doc)) return elsewhere(r.file, r.start, r.end);
      return new vscode.Location(doc.uri, range(doc, r.start, r.end));
    }
  }));

  context.subscriptions.push(vscode.languages.registerSignatureHelpProvider(selector, {
    provideSignatureHelp(doc, pos) {
      current(doc);
      const r = call('signature', doc.getText(), doc.offsetAt(pos));
      if (!r.label) return null;
      const info = new vscode.SignatureInformation(r.label, r.doc ? new vscode.MarkdownString(r.doc) : undefined);
      info.parameters = r.params.map(p => new vscode.ParameterInformation(p));
      const help = new vscode.SignatureHelp();
      help.signatures = [info];
      help.activeSignature = 0;
      help.activeParameter = Math.max(0, r.active);
      return help;
    }
  }, { triggerCharacters: ['(', ','], retriggerCharacters: [':'] }));

  context.subscriptions.push(vscode.languages.registerReferenceProvider(selector, {
    provideReferences(doc, pos) {
      current(doc);
      return call('references', doc.getText(), doc.offsetAt(pos)).map(r => r.file ? elsewhere(r.file, r.start, r.end)
        : new vscode.Location(doc.uri, range(doc, r.start, r.end)));
    }
  }));

  context.subscriptions.push(vscode.languages.registerDocumentHighlightProvider(selector, {
    provideDocumentHighlights(doc, pos) {
      current(doc);
      return call('references', doc.getText(), doc.offsetAt(pos)).filter(r => !r.file)
        .map(r => new vscode.DocumentHighlight(range(doc, r.start, r.end)));
    }
  }));

  context.subscriptions.push(vscode.languages.registerCodeActionsProvider(selector, {
    provideCodeActions(doc, selection, context) {
      const out = [];
      for (const d of context.diagnostics) {
        const edits = fixes.get(d);
        if (!edits) continue;
        edits.forEach((e, n) => {
          const action = new vscode.CodeAction(e.title, vscode.CodeActionKind.QuickFix);
          action.edit = new vscode.WorkspaceEdit();
          action.edit.replace(doc.uri, new vscode.Range(doc.positionAt(e.start), doc.positionAt(e.end)), e.text);
          action.diagnostics = [d];
          action.isPreferred = n === 0;
          out.push(action);
        });
      }
      current(doc);
      for (const e of call('actions', doc.getText(), doc.offsetAt(selection.start), doc.offsetAt(selection.end))) {
        const action = new vscode.CodeAction(e.title, vscode.CodeActionKind.RefactorRewrite);
        action.edit = new vscode.WorkspaceEdit();
        action.edit.replace(doc.uri, new vscode.Range(doc.positionAt(e.start), doc.positionAt(e.end)), e.text);
        out.push(action);
      }
      return out;
    }
  }, { providedCodeActionKinds: [vscode.CodeActionKind.QuickFix, vscode.CodeActionKind.RefactorRewrite] }));

  context.subscriptions.push(vscode.languages.registerRenameProvider(selector, {
    provideRenameEdits(doc, pos, name) {
      current(doc);
      const r = call('rename', doc.getText(), doc.offsetAt(pos), name);
      if (r.error) throw new Error(r.error);
      const edit = new vscode.WorkspaceEdit();
      for (const e of r.edits) edit.replace(doc.uri, range(doc, e.start, e.end), e.text);
      for (const [file, list] of Object.entries(r.others || {})) {
        for (const e of list) {
          const at = elsewhere(file, e.start, e.end);
          edit.replace(at.uri, at.range, e.text);
        }
      }
      return edit;
    }
  }));

  context.subscriptions.push(vscode.languages.registerDocumentFormattingEditProvider(selector, {
    provideDocumentFormattingEdits(doc) {
      const r = call('format', doc.getText());
      if (!r.ok) {
        vscode.window.setStatusBarMessage('Xero: не отформатировано — сначала исправь ошибки', 4000);
        return [];
      }
      if (r.text === doc.getText()) return [];
      const all = new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length));
      return [vscode.TextEdit.replace(all, r.text)];
    }
  }));

  context.subscriptions.push(vscode.languages.registerDocumentSymbolProvider(selector, {
    provideDocumentSymbols(doc) {
      const kinds = { event: vscode.SymbolKind.Event, function: vscode.SymbolKind.Function, process: vscode.SymbolKind.Method };
      const russian = { event: 'событие', function: 'функция', process: 'процесс' };
      return call('symbols', doc.getText()).map(s => new vscode.DocumentSymbol(s.name, russian[s.kind], kinds[s.kind],
        range(doc, s.start, s.end), range(doc, s.nameStart, s.nameEnd)));
    }
  }));

  context.subscriptions.push(vscode.languages.registerInlayHintsProvider(selector, {
    provideInlayHints(doc, visible) {
      const out = [];
      current(doc);
      for (const h of call('hints', doc.getText())) {
        const at = doc.positionAt(h.at);
        if (!visible.contains(at)) continue;
        const hint = new vscode.InlayHint(at, h.label, vscode.InlayHintKind.Parameter);
        hint.paddingRight = true;
        out.push(hint);
      }
      return out;
    }
  }));

  const legend = new vscode.SemanticTokensLegend(['variable'], ['game', 'save', 'local', 'line', 'const']);
  context.subscriptions.push(vscode.languages.registerDocumentSemanticTokensProvider(selector, {
    provideDocumentSemanticTokens(doc) {
      const tokens = new vscode.SemanticTokensBuilder(legend);
      for (const m of call('variables', doc.getText())) tokens.push(range(doc, m.start, m.end), 'variable', [m.scope]);
      return tokens.build();
    }
  }, legend));

  context.subscriptions.push(
    vscode.commands.registerCommand('xero.upload', () => upload(false)),
    vscode.commands.registerCommand('xero.add', () => upload(true)),
    vscode.commands.registerCommand('xero.pull', pull),
    vscode.commands.registerCommand('xero.pullGame', pullGame),
    vscode.commands.registerCommand('xero.diff', diff),
    vscode.commands.registerCommand('xero.updateModules', updateModules),
    vscode.commands.registerCommand('xero.build', build));

  const ear = setInterval(listen, 3000);
  context.subscriptions.push({ dispose: () => clearInterval(ear) });

  context.subscriptions.push(vscode.workspace.onDidSaveTextDocument(async doc => {
    if (doc.languageId !== 'xero' || !vscode.workspace.getConfiguration('xero').get('uploadOnSave')) return;
    if (vscode.window.activeTextEditor?.document !== doc || !await gameReady()) return;
    upload(false);
  }));
}

let gameLog = null;
let heardAt = 0;

async function listen() {
  if (vscode.workspace.getConfiguration('xero').get('gameLog') === false || !linkFile()) return;
  try {
    const got = await game('GET', '/log?since=' + heardAt, null, 2000);
    if (heardAt === 0) {
      heardAt = got.now;
      return;
    }
    heardAt = got.now;
    if (!got.lines.length) return;
    if (!gameLog) gameLog = vscode.window.createOutputChannel('Xero: игра');
    for (const line of got.lines) {
      gameLog.appendLine(line);
      if (/ошибк|error/i.test(line)) vscode.window.showWarningMessage('Xero, сервер: ' + line.slice(0, 200));
    }
  } catch (e) {
    heardAt = 0;
  }
}

function linkFile() {
  try {
    const o = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.xerocode', 'link.json'), 'utf8'));
    return typeof o.port === 'number' && typeof o.token === 'string' ? o : null;
  } catch (e) {
    return null;
  }
}

function game(method, where, body, wait) {
  const link = linkFile();
  if (!link) return Promise.reject(new Error('игра с XeroCode не запущена'));
  return new Promise((resolve, reject) => {
    const headers = { 'X-Xero-Token': link.token };
    if (body) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(body);
    }
    const req = http.request({ host: '127.0.0.1', port: link.port, path: where, method, headers, timeout: wait }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        let answer;
        try {
          answer = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        } catch (e) {
          reject(new Error('игра ответила непонятно'));
          return;
        }
        if (answer.ok) resolve(answer);
        else reject(new Error(answer.error || 'игра ответила ' + res.statusCode));
      });
    });
    req.on('timeout', () => req.destroy(new Error('игра не ответила за ' + Math.round(wait / 1000) + ' с')));
    req.on('error', () => reject(new Error('игра с XeroCode не отвечает')));
    if (body) req.write(body);
    req.end();
  });
}

async function gameReady() {
  if (vscode.workspace.getConfiguration('xero').get('useGame') === false || !linkFile()) return false;
  try {
    const s = await game('GET', '/status', null, 2000);
    return !!(s.online && s.dev);
  } catch (e) {
    return false;
  }
}

function elsewhere(file, start, end) {
  const open = vscode.workspace.textDocuments.find(t => fileName(t) === file);
  const uri = vscode.Uri.file(file);
  if (open) return new vscode.Location(uri, range(open, start, end));
  const text = reader(file) || '';
  return new vscode.Location(uri, new vscode.Range(position(text, start), position(text, Math.max(end, start + 1))));
}

function position(text, offset) {
  let line = 0, column = 0;
  for (let i = 0; i < offset && i < text.length; i++) {
    if (text[i] === '\n') { line++; column = 0; } else column++;
  }
  return new vscode.Position(line, column);
}

function active() {
  const editor = vscode.window.activeTextEditor;
  if (!editor || editor.document.languageId !== 'xero') {
    vscode.window.showErrorMessage('Xero: открой файл .xero');
    return null;
  }
  return editor.document;
}

function compile(doc) {
  homeDir = home(doc);
  const r = call('build', fileName(doc), doc.getText(), reader);
  if (!r.ok) {
    const errors = r.diagnostics.filter(d => d.severity === 'error').length;
    vscode.window.showErrorMessage('Xero: не собрано — ошибок ' + errors + ' (список — в «Проблемах»)');
    vscode.commands.executeCommand('workbench.actions.view.problems');
    return null;
  }
  return r;
}

async function build() {
  const doc = active();
  if (!doc) return;
  await modulesReady(doc);
  const r = compile(doc);
  if (!r) return;
  if (doc.uri.scheme !== 'file') {
    const json = await vscode.workspace.openTextDocument({ language: 'json', content: r.module });
    vscode.window.showTextDocument(json);
    return;
  }
  const out = doc.uri.fsPath.replace(/\.xero$/i, '') + '.json';
  fs.writeFileSync(out, r.module + '\n', 'utf8');
  vscode.window.showInformationMessage('Xero: собрано — строк ' + r.lines + ', блоков ' + r.blocks + ' → ' + path.basename(out));
}

function copyPath(dir) {
  for (let p = path.resolve(dir); ; p = path.dirname(p)) {
    const f = path.join(p, '.xero', 'world.json');
    if (fs.existsSync(f)) return f;
    if (path.dirname(p) === p) break;
  }
  return path.join(path.resolve(dir), '.xero', 'world.json');
}

function readCopy(file) {
  try {
    const o = JSON.parse(fs.readFileSync(file, 'utf8'));
    return { known: o.known || null, how: o.how || null, sent: o.sent || [], handlers: o.handlers || [] };
  } catch (e) {
    return { known: null, how: null, sent: [], handlers: [] };
  }
}

function saveCopy(file, copy) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const o = { xero: 1 };
  if (copy.known) o.known = copy.known;
  if (copy.how) o.how = copy.how;
  o.sent = copy.sent;
  o.handlers = copy.handlers;
  fs.writeFileSync(file, JSON.stringify(o) + '\n', 'utf8');
}

function sentHandlers(copy) {
  const out = [];
  for (const s of copy.sent) for (const l of s.lines) {
    if (l.kind === 'EVENT') out.push({ type: 'event', event: l.name, sentAt: s.at });
    else out.push({ type: l.kind === 'PROCESS' ? 'process' : 'function', name: l.name, sentAt: s.at });
  }
  return out;
}

function keyOf(h) {
  return h.type === 'event' ? 'event:' + h.event : h.type + ':' + h.name;
}

function title(h) {
  const where = typeof h.position === 'number' ? ' (строка ' + h.position + ')' : '';
  if (h.type === 'event') return 'событие ' + h.event + where;
  return (h.type === 'process' ? 'процесс' : 'функция') + ' «' + h.name + '»' + where;
}

function when(iso) {
  const d = new Date(iso);
  return d.toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function describe(copy) {
  if (!copy.known) return 'известны только строки, дописанные отсюда';
  return (copy.how === 'replace' ? 'то, что Xero залил заменой ' : 'скачана ') + when(copy.known);
}

async function diff() {
  const doc = active();
  if (!doc) return;
  await modulesReady(doc);
  const r = compile(doc);
  if (!r) return;
  const dir = home(doc);
  const copy = dir ? readCopy(copyPath(dir)) : { known: null, sent: [], handlers: [] };
  let world = copy.known || copy.sent.length ? JSON.stringify({ handlers: copy.handlers.concat(sentHandlers(copy)) }) : '';
  let where = world ? describe(copy) : '';
  if (await gameReady()) {
    try {
      const got = await vscode.window.withProgress({ location: vscode.ProgressLocation.Window, title: 'Xero: читаю код мира из игры' },
        () => game('GET', '/world', null, 610000));
      world = JSON.stringify({ handlers: got.handlers });
      where = 'мир прямо сейчас';
    } catch (e) {
      vscode.window.showWarningMessage('Xero: мир не прочитан — ' + e.message);
    }
  }
  if (!world) {
    vscode.window.showInformationMessage('Xero: сверять не с чем — скачай код мира или запусти игру');
    return;
  }
  const plan = call('plan', world, r.module);
  const out = changes();
  out.clear();
  out.appendLine('Сверено: ' + where);
  out.appendLine('Без изменений: ' + plan.same + ', изменено: ' + plan.changed.length + ', новых: ' + plan.added.length + ', сотрётся: ' + plan.missing.length);
  for (const [title, list] of [['изменено', plan.changed], ['новые', plan.added], ['сотрётся', plan.missing]]) {
    if (list.length) out.appendLine('\n' + title + ':\n  ' + list.join('\n  '));
  }
  out.show(true);
}

let changesChannel = null;

function changes() {
  if (!changesChannel) changesChannel = vscode.window.createOutputChannel('Xero: что изменится');
  return changesChannel;
}

async function upload(add) {
  const doc = active();
  if (!doc) return;
  await modulesReady(doc);
  const r = compile(doc);
  if (!r) return;
  const dir = doc.uri.scheme === 'file' ? path.dirname(doc.uri.fsPath) : (vscode.workspace.workspaceFolders || [])[0]?.uri.fsPath;
  const file = dir ? copyPath(dir) : null;
  const copy = file ? readCopy(file) : { known: null, how: null, sent: [], handlers: [] };
  const exists = !!copy.known || copy.sent.length > 0;
  const module = JSON.parse(r.module);
  let body = r.module;
  let rows = null;
  if (!add) {
    const world = JSON.stringify({ handlers: copy.handlers.concat(sentHandlers(copy)) });
    const plan = call('plan', exists ? world : '', r.module);
    if (plan.missing.length > 0) {
      const shown = plan.missing.slice(0, LISTED).join('\n') + (plan.missing.length > LISTED ? '\n… и ещё ' + (plan.missing.length - LISTED) : '');
      const pick = await vscode.window.showWarningMessage(
        'В мире есть строки, которых нет в проекте, — замена их сотрёт (копия мира: ' + describe(copy) + ')',
        { modal: true, detail: shown }, 'Стереть их и залить', 'Только дописать');
      if (!pick) return;
      if (pick === 'Только дописать') return upload(true);
    }
    body = plan.module;
    if (plan.rows && vscode.workspace.getConfiguration('xero').get('lineUpload') !== false) rows = JSON.parse(plan.rows);
    if (plan.bytes > plan.limit) {
      vscode.window.showErrorMessage('Xero: модуль больше 5 МБ — сервер такой не примет');
      return;
    }
  } else {
    const world = copy.handlers.concat(sentHandlers(copy));
    const doubled = [];
    for (const h of module.handlers) {
      if (h.type === 'event') continue;
      const twin = world.find(w => keyOf(w) === keyOf(h));
      if (twin) doubled.push(title(h) + (twin.sentAt ? ' — уже отправлена ' + when(twin.sentAt) : ' — уже есть в мире'));
    }
    if (doubled.length > 0) {
      const pick = await vscode.window.showWarningMessage(
        'При дописывании сервер не заменит эти строки, а задвоит их',
        { modal: true, detail: doubled.slice(0, LISTED).join('\n') }, 'Задвоить всё равно', 'Заменить весь код мира');
      if (!pick) return;
      if (pick === 'Заменить весь код мира') return upload(false);
    }
  }
  const remember = () => {
    if (!file) return;
    if (add) {
      const lines = module.handlers.map(h => ({ kind: h.type === 'event' ? 'EVENT' : h.type === 'process' ? 'PROCESS' : 'FUNCTION', name: h.type === 'event' ? h.event : h.name }));
      copy.sent.push({ at: new Date().toISOString(), lines });
    } else {
      copy.handlers = JSON.parse(body).handlers;
      copy.known = new Date().toISOString();
      copy.how = 'replace';
      copy.sent = [];
    }
    saveCopy(file, copy);
  };
  const connected = async inGame => {
    for (const m of r.connected || []) {
      if (!inGame) {
        vscode.window.showInformationMessage('Xero: модуль «' + m.name + '» подключи в игре через /module');
        continue;
      }
      try {
        const said = await game('POST', '/connect', JSON.stringify(m), 40000);
        vscode.window.showInformationMessage('Xero: модуль «' + m.name + '»: ' + said.said);
      } catch (e) {
        vscode.window.showErrorMessage('Xero: модуль «' + m.name + '» не подключён — ' + e.message);
      }
    }
  };
  if (await gameReady()) {
    if (rows && rows.length === 0) {
      await connected(true);
      vscode.window.setStatusBarMessage('Xero: в мире уже это', 4000);
      return;
    }
    if (rows) {
      let placed;
      try {
        placed = await vscode.window.withProgress({ location: vscode.ProgressLocation.Window, title: 'Xero: заменяю строки: ' + rows.length },
          () => game('POST', '/lines', JSON.stringify({ lines: rows }), 310000));
      } catch (e) {
        vscode.window.showErrorMessage('Xero: строки не заменены — ' + e.message);
        return;
      }
      const bad = placed.lines.filter(l => !l.ok);
      if (bad.length) {
        vscode.window.showErrorMessage('Xero: не встало строк ' + bad.length + ' — ' + bad.map(l => l.position + ': ' + l.said).join('; '));
        return;
      }
      remember();
      await connected(true);
      vscode.window.showInformationMessage('Xero: заменено строк ' + rows.length + ', мир не перезапускался');
      return;
    }
    let done;
    try {
      done = await vscode.window.withProgress({ location: vscode.ProgressLocation.Window, title: 'Xero: заливаю в мир' },
        () => game('POST', '/upload?mode=' + (add ? 'add' : 'replace'), body, 150000));
    } catch (e) {
      vscode.window.showErrorMessage('Xero: не залито — ' + e.message);
      return;
    }
    const said = (done.answers || []).join(' · ');
    if (done.done === false) {
      vscode.window.showWarningMessage('Xero: сервер не подтвердил заливку — проверь чат' + (said ? '. Сервер: ' + said : ''));
      return;
    }
    remember();
    await connected(true);
    vscode.window.showInformationMessage('Xero: ' + (add ? 'дописано в мир' : 'мир заменён') + ': строк ' + r.lines + ', блоков ' + r.blocks
      + (said ? '. Сервер: ' + said : ''));
    return;
  }
  let where;
  try {
    where = chosenStore();
  } catch (e) {
    vscode.window.showErrorMessage('Xero: не залито — ' + e.message);
    return;
  }
  let url;
  try {
    url = await store(body, where);
  } catch (e) {
    vscode.window.showErrorMessage('Xero: не залито — ' + e.message);
    return;
  }
  const command = '/module loadUrl ' + (add ? '' : 'force ') + url;
  await vscode.env.clipboard.writeText(command);
  remember();
  await connected(false);
  vscode.window.showInformationMessage('Xero: команда в буфере обмена — вставь её в чат в /dev ' + notice(url)
    + '. ' + (add ? 'Допишется' : 'Заменит весь код мира') + ': строк ' + r.lines + ', блоков ' + r.blocks + '.');
}

function request(url, method, body, wait = 30000) {
  return new Promise((resolve, reject) => {
    const lib = url.startsWith('https:') ? https : http;
    const req = lib.request(url, { method, timeout: wait, headers: body ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), 'User-Agent': 'Xero' } : {} }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, text: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('timeout', () => req.destroy(new Error('сервер не ответил за ' + Math.round(wait / 1000) + ' с')));
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

function chosenStore() {
  const custom = String(vscode.workspace.getConfiguration('xero').get('storeUrl') || '').trim();
  if (!custom) return STORE;
  let parsed = null;
  try {
    parsed = new URL(custom);
  } catch (e) {
    parsed = null;
  }
  if (!parsed || (parsed.protocol !== 'http:' && parsed.protocol !== 'https:'))
    throw new Error('в настройке xero.storeUrl нужен адрес http://хост:порт/путь/, а там «' + custom + '»');
  const base = custom.endsWith('/') ? custom : custom + '/';
  return { uploads: [base + UPLOAD], code: base + 'code/' };
}

async function store(body, where) {
  let own;
  try {
    return await uploadOwn(body, where);
  } catch (e) {
    own = e;
  }
  try {
    return await uploadOfficial(body);
  } catch (e) {
    throw new Error(own.message + '; запасной m.justmc.ru: ' + e.message);
  }
}

async function uploadOfficial(body) {
  let r;
  try {
    r = await request(OFFICIAL + 'upload', 'POST', body);
  } catch (e) {
    throw new Error('m.justmc.ru: ' + e.message);
  }
  let answer = null;
  try {
    answer = JSON.parse(r.text);
  } catch (e) {
    throw new Error('m.justmc.ru ответил ' + r.status);
  }
  if (answer && answer.error) throw new Error('m.justmc.ru не принял код: ' + String(answer.error).slice(0, 120));
  if (r.status === 200 && answer && typeof answer.id === 'string' && /^[0-9A-Za-z_-]{4,64}$/.test(answer.id))
    return OFFICIAL + answer.id;
  throw new Error('m.justmc.ru ответил ' + r.status);
}

function notice(url) {
  return url.startsWith(OFFICIAL) ? 'в течение минуты (ссылка с m.justmc.ru живёт около 80 с)'
    : 'в течение ' + LIFETIME + ' с (ссылка работает один раз)';
}

async function uploadOwn(body, where) {
  let last = null;
  for (const upload of where.uploads) {
    try {
      const r = await request(upload, 'POST', body, 12000);
      if (r.status === 200) {
        const id = JSON.parse(r.text).id;
        if (/^[0-9a-f]{32}$/.test(id)) return where.code + id + '.json';
        throw new Error('хранилище ответило непонятно');
      }
      last = new Error('хранилище ответило ' + r.status);
      if (r.status !== 502 && r.status !== 503) throw last;
    } catch (e) {
      last = e;
    }
  }
  throw last || new Error('загрузка не удалась');
}

async function pull() {
  const link = await vscode.window.showInputBox({
    prompt: 'Ссылка из /editor download (клик по ней в чате → «Копировать»)',
    placeHolder: 'http://api.creative.justmc.io/public/creative/handlers/…?token=…',
    ignoreFocusOut: true
  });
  if (!link) return;
  let url;
  try {
    url = new URL(link.trim());
  } catch (e) {
    url = null;
  }
  if (!url || url.hostname !== JUSTMC || !/^\/public\/creative\/handlers\/[0-9a-fA-F-]{32,40}$/.test(url.pathname) || !url.searchParams.get('token')) {
    vscode.window.showErrorMessage('Xero: это не ссылка /editor download');
    return;
  }
  let text = null, last = null;
  for (const scheme of ['https:', 'http:']) {
    try {
      const r = await request(scheme + '//' + url.host + url.pathname + url.search, 'GET');
      if (r.status === 200) { text = r.text; break; }
      last = new Error(r.status === 404 ? 'ссылка уже использована — сделай /editor download ещё раз' : 'сервер JustMC ответил ' + r.status);
      if (r.status === 404) break;
    } catch (e) {
      last = e;
    }
  }
  if (text === null) {
    vscode.window.showErrorMessage('Xero: код мира не получен — ' + (last ? last.message : 'нет ответа'));
    return;
  }
  await openWorld(text);
}

async function pullGame() {
  let answer;
  try {
    answer = await vscode.window.withProgress({ location: vscode.ProgressLocation.Window, title: 'Xero: читаю код мира из игры' },
      () => game('GET', '/world', null, 610000));
  } catch (e) {
    vscode.window.showErrorMessage('Xero: код мира не получен — ' + e.message);
    return;
  }
  await openWorld(JSON.stringify({ handlers: answer.handlers }));
}

async function openWorld(text) {
  const r = call('decompile', text);
  const editor = vscode.window.activeTextEditor;
  const dir = editor && editor.document.uri.scheme === 'file' ? path.dirname(editor.document.uri.fsPath)
    : (vscode.workspace.workspaceFolders || [])[0]?.uri.fsPath;
  if (dir) {
    const file = copyPath(dir);
    const copy = readCopy(file);
    copy.handlers = JSON.parse(text).handlers;
    copy.known = new Date().toISOString();
    copy.how = 'pull';
    copy.sent = [];
    saveCopy(file, copy);
  }
  const target = dir ? path.join(dir, 'world.xero') : null;
  if (target && !fs.existsSync(target)) {
    fs.writeFileSync(target, r.text, 'utf8');
    vscode.window.showTextDocument(await vscode.workspace.openTextDocument(target));
  } else {
    vscode.window.showTextDocument(await vscode.workspace.openTextDocument({ language: 'xero', content: r.text }));
    if (target) vscode.window.showInformationMessage('Xero: world.xero уже есть — код мира открыт рядом, без сохранения');
  }
  vscode.window.showInformationMessage('Xero: код мира — строк ' + r.lines + ', блоков ' + r.blocks + '. Копия мира сохранена для проверок перед заливкой.');
}

function deactivate() {}

module.exports = { activate, deactivate };
