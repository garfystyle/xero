'use strict';

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');

const report = [];

function check(name, ok, detail) {
  report.push({ name, ok: !!ok, detail: detail === undefined ? '' : String(detail).slice(0, 300) });
}

async function until(test, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = test();
    if (v) return v;
    await new Promise(r => setTimeout(r, 100));
  }
  return test();
}

async function open(file, text) {
  if (text !== undefined) fs.writeFileSync(file, text, 'utf8');
  const doc = await vscode.workspace.openTextDocument(file);
  await vscode.window.showTextDocument(doc);
  return doc;
}

function at(doc, needle, shift) {
  return doc.positionAt(doc.getText().indexOf(needle) + (shift || 0));
}

async function run() {
  const root = vscode.workspace.workspaceFolders[0].uri.fsPath;
  const out = process.env.XERO_REPORT;
  try {
    const ext = vscode.extensions.getExtension('xero.xero');
    check('расширение установлено из .vsix', ext, ext ? ext.extensionPath : 'нет');
    const main = await open(path.join(root, 'main.xero'));
    check('язык файла — xero', main.languageId === 'xero', main.languageId);
    const locales = vscode.workspace.getConfiguration('editor', { languageId: 'xero' }).get('unicodeHighlight.allowedLocales') || {};
    check('русские буквы не подсвечиваются как похожие на латиницу', locales.ru === true, JSON.stringify(locales));

    const mainProblems = await until(() => {
      const d = vscode.languages.getDiagnostics(main.uri);
      return d.length ? d : null;
    }, 15000);
    check('ошибка на лету в main.xero', (mainProblems || []).some(d => d.message.includes('нет действия «send_mesage»')
      && d.message.includes('send_message')), (mainProblems || []).map(d => d.message).join(' | '));
    const kit = vscode.Uri.file(path.join(root, 'kit.xero'));
    const kitProblems = await until(() => {
      const d = vscode.languages.getDiagnostics(kit);
      return d.length ? d : null;
    }, 5000);
    check('ошибка в импортированном kit.xero', (kitProblems || []).some(d => d.message.includes('mergng')),
      (kitProblems || []).map(d => d.message).join(' | '));

    const play = await open(path.join(root, 'play.xero'), [
      'func greet(name) {',
      '    send_message("привет, ${name}")',
      '}',
      '',
      'when player_join {',
      '    game score = 1',
      '    send_mes',
      '    отправ',
      '    score += 1',
      '    greet("x")',
      '    x = value.current_h',
      '    play_sound(sound(entity.player.lev))',
      '    give_items(item(diamond_sword, enchantments: {sharp}))',
      '}',
      ''].join('\n'));
    await until(() => vscode.languages.getDiagnostics(play.uri).length, 5000);

    const tokens = await vscode.commands.executeCommand('_workbench.captureSyntaxTokens', play.uri) || [];
    const text = tokens.find(t => t.c.includes('привет'));
    const closing = tokens.find((t, i) => i > 0 && tokens[i - 1].c.includes('${name}') && t.c.startsWith(')'))
      || tokens.find(t => t.c === ')' || t.c === '")' || t.c.startsWith(')'));
    check('строка подсвечивается строкой', text && /string\.quoted/.test(text.t), text ? text.c + ' → ' + text.t : 'нет');
    check('скобка после строки — не строка', closing && !/string\.quoted/.test(closing.t), closing ? closing.c + ' → ' + closing.t : 'нет');

    const labels = async (pos) => {
      const list = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', play.uri, pos);
      return list.items.map(i => typeof i.label === 'string' ? i.label : i.label.label);
    };
    let l = await labels(at(play, '    send_mes\n', 12));
    check('дополнение: send_mes → send_message первым', l[0] === 'send_message', l.slice(0, 5));
    l = await labels(at(play, 'отправ', 6));
    check('дополнение по-русски: отправ → send_message', l.includes('send_message'), l.slice(0, 8));
    l = await labels(at(play, 'value.current_h', 15));
    check('дополнение: value.current_h → current_health', l.includes('current_health'), l.slice(0, 5));
    l = await labels(at(play, 'entity.player.lev', 17));
    check('дополнение: звук entity.player.levelup', l.includes('entity.player.levelup'), l.slice(0, 5));
    l = await labels(at(play, '{sharp}', 6));
    check('дополнение: чары в item(…, enchantments: {…})', l[0] === 'sharpness', l.slice(0, 5));

    const hover = async (pos) => {
      const h = await vscode.commands.executeCommand('vscode.executeHoverProvider', play.uri, pos);
      return h.map(x => x.contents.map(c => typeof c === 'string' ? c : c.value).join('\n')).join('\n');
    };
    let h = await hover(at(play, 'send_message("привет', 3));
    check('наведение: описание действия', h.includes('Отправить сообщение') && h.includes('Отправляет сообщение'), h);
    h = await hover(at(play, 'greet("x")', 2));
    check('наведение: заголовок функции', h.includes('func greet(name)'), h);

    const defs = await vscode.commands.executeCommand('vscode.executeDefinitionProvider', play.uri, at(play, 'greet("x")', 2));
    const def = defs && defs[0];
    const target = def && (def.range || def.targetRange);
    check('переход к функции', target && target.start.line === 0, target ? target.start.line + ':' + target.start.character : 'нет');

    const edit = await vscode.commands.executeCommand('vscode.executeDocumentRenameProvider', play.uri, at(play, 'score += 1', 1), 'очки');
    const changes = edit ? edit.get(play.uri) : [];
    check('переименование переменной: 2 места', changes.length === 2, changes.length);

    const sig = await vscode.commands.executeCommand('vscode.executeSignatureHelpProvider', play.uri,
      at(play, 'send_message("привет', 13), '(');
    const sigLabel = sig && sig.signatures[0] ? sig.signatures[0].label : '';
    check('подсказка аргументов при наборе', sigLabel.startsWith('send_message(messages: '), sigLabel);

    const refs = await vscode.commands.executeCommand('vscode.executeReferenceProvider', play.uri, at(play, 'score += 1', 1));
    check('все упоминания переменной: 2', refs && refs.length === 2, refs ? refs.length : 'нет');
    const lit = await vscode.commands.executeCommand('vscode.executeDocumentHighlights', play.uri, at(play, 'score += 1', 1));
    check('подсветка упоминаний: 2', lit && lit.length === 2, lit ? lit.length : 'нет');

    const lined = await open(path.join(root, 'hints.xero'), 'game g = 1\nconst MAX = 5\nwhen player_join {\n    give_items(item(stick), 3)\n    x = get_list_value([1], 0)\n    send_message("${x} ${g} ${MAX}")\n}\n');
    const whole = new vscode.Range(0, 0, lined.lineCount, 0);
    const inlay = await vscode.commands.executeCommand('vscode.executeInlayHintProvider', lined.uri, whole);
    const inlayLabels = (inlay || []).map(h => typeof h.label === 'string' ? h.label : h.label.map(p => p.value).join(''));
    check('названия аргументов в строке', inlayLabels.includes('items:') && inlayLabels.includes('list:') && !inlayLabels.includes('variable:'),
      inlayLabels.join(' '));
    const legend = await vscode.commands.executeCommand('vscode.provideDocumentSemanticTokensLegend', lined.uri);
    const painted = await vscode.commands.executeCommand('vscode.provideDocumentSemanticTokens', lined.uri);
    check('цвет переменной по области', legend && legend.tokenModifiers.includes('game') && painted && painted.data.length >= 15,
      legend ? legend.tokenModifiers.join(',') + ' ' + (painted ? painted.data.length : 'нет') : 'нет');

    const typo = (mainProblems || []).find(d => d.message.includes('send_mesage'));
    const fixes = typo ? await vscode.commands.executeCommand('vscode.executeCodeActionProvider', main.uri, typo.range) : [];
    const fix = (fixes || []).find(a => a.title === 'Заменить на «send_message»');
    check('быстрое исправление опечатки (Ctrl+.)', fix && fix.edit, (fixes || []).map(a => a.title).join(' | '));
    if (fix && fix.edit) {
      await vscode.workspace.applyEdit(fix.edit);
      check('исправление применилось', main.getText().includes('send_message(') && !main.getText().includes('send_mesage'),
        main.getText().slice(0, 200));
    }

    const copper = 'H4sIAAAAAAAA/+NiYGBm4HZJLEkMSy0qzszPY2AQVudgYMpMYZDKzcxLTS5KTCuxKk9NLMlILUpNiU/OLyhILWJmYE3OL80rYWBgYGQAAOgAAFtDAAAA';
    const copied = await open(path.join(root, 'copied.xero'), 'when player_join {\n    give_items(item("' + copper + '"))\n}\n');
    const inside = copied.positionAt(copied.getText().indexOf('H4sI') + 5);
    const rewrites = await vscode.commands.executeCommand('vscode.executeCodeActionProvider', copied.uri,
      new vscode.Range(inside, inside));
    const spell = (rewrites || []).find(a => a.title === 'Расписать предмет полями');
    check('предмет из XeroCode расписывается полями (Ctrl+.)', spell && spell.edit, (rewrites || []).map(a => a.title).join(' | '));
    if (spell && spell.edit) {
      await vscode.workspace.applyEdit(spell.edit);
      check('предмет расписан', copied.getText().includes('give_items(item(weathered_copper))'), copied.getText().slice(0, 200));
    }

    const symbols = await vscode.commands.executeCommand('vscode.executeDocumentSymbolProvider', play.uri);
    check('список строк файла', symbols && symbols.map(s => s.name).join(',') === 'greet,player_join',
      symbols ? symbols.map(s => s.name).join(',') : 'нет');

    const snip = await open(path.join(root, 'snip.xero'), 'wh');
    const editor = vscode.window.activeTextEditor;
    const pick = async (pos, name) => {
      const list = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', snip.uri, pos);
      return list.items.find(i => (typeof i.label === 'string' ? i.label : i.label.label) === name);
    };
    const end = () => snip.positionAt(snip.getText().length);
    const plain = () => snip.getText().replace(/\r\n/g, '\n');
    const insert = async (item) => {
      await editor.insertSnippet(item.insertText, item.range.inserting ? item.range.replacing : item.range);
    };
    const when = await pick(end(), 'when');
    check('заготовка when сразу открывает события', when && when.command && when.command.command === 'editor.action.triggerSuggest',
      when ? JSON.stringify(when.command) : 'нет');
    await insert(when);
    const join = await pick(end(), 'player_join');
    await insert(join);
    const cursor = editor.selection.active;
    check('событие вставилось блоком, курсор внутри', plain() === 'when player_join {\n    \n}' && cursor.line === 1 && cursor.character === 4,
      JSON.stringify(plain()) + ' курсор ' + cursor.line + ':' + cursor.character);
    await editor.insertSnippet(new vscode.SnippetString('add_custom_craf'), editor.selection.active);
    const craft = await pick(editor.selection.active, 'add_custom_crafting_recipe');
    await insert(craft);
    check('сетка верстака', plain().includes('add_custom_crafting_recipe("id", item(diamond_sword), [\n        _, _, _,\n        _, _, _,\n        _, _, _,\n    ], recipe_type: shaped)'),
      JSON.stringify(snip.getText()));
    await vscode.commands.executeCommand('leaveSnippet');
    await editor.edit(e => e.replace(new vscode.Range(snip.positionAt(0), end()), 'when player_join {\n    if x == 1 {\n    } \n}'));
    const after = snip.positionAt(snip.getText().indexOf('} ') + 2);
    const elseItem = await pick(after, 'else');
    check('else после блока', elseItem && elseItem.insertText && elseItem.insertText.value === 'else {\n\t$0\n}',
      elseItem ? JSON.stringify(elseItem.insertText) : 'нет');

    const messy = await open(path.join(root, 'messy.xero'), 'when player_join{send_message( "a" ,"b");x=1}\n');
    const edits = await vscode.commands.executeCommand('vscode.executeFormatDocumentProvider', messy.uri,
      { tabSize: 4, insertSpaces: true });
    const we = new vscode.WorkspaceEdit();
    for (const e of edits || []) we.replace(messy.uri, e.range, e.newText);
    await vscode.workspace.applyEdit(we);
    check('формат', messy.getText() === 'when player_join {\n    send_message("a", "b")\n    x = 1\n}\n', JSON.stringify(messy.getText()));

    const fine = await open(path.join(root, 'fine.xero'), 'when player_join {\n    send_message("Xero 0.6: залито из VS Code")\n}\n');
    await vscode.commands.executeCommand('xero.build');
    const built = path.join(root, 'fine.json');
    check('команда «Собрать JSON»', fs.existsSync(built) && JSON.parse(fs.readFileSync(built, 'utf8')).handlers.length === 1,
      fs.existsSync(built) ? fs.readFileSync(built, 'utf8').slice(0, 120) : 'нет файла');

    fs.mkdirSync(path.join(root, '.xero', 'modules'), { recursive: true });
    fs.writeFileSync(path.join(root, '.xero', 'modules', 'lib.json'), JSON.stringify({ handlers: [{ type: 'function', name: 'Lib.hello', position: 0, operations: [],
      values: [{ name: 'parameters', value: { type: 'array', values: [{ type: 'parameter', type_key: 'singular', name: 'who', value_type: 'text', is_required: 'true', default_value: '{}' }] } }] }] }), 'utf8');
    const lib = await open(path.join(root, 'lib.xero'), 'import "lib"\n\nwhen player_join {\n    Lib.hello()\n    \n}\n');
    const libProblems = await until(() => {
      const list = vscode.languages.getDiagnostics(lib.uri);
      return list.length ? list : null;
    }, 8000);
    check('модуль: вызов проверяется по его параметрам', libProblems && libProblems.some(d => d.message.includes('не передан параметр «who»')),
      (libProblems || []).map(d => d.message).join(' | '));
    const offered = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', lib.uri, new vscode.Position(4, 4));
    const offeredNames = offered.items.map(i => typeof i.label === 'string' ? i.label : i.label.label);
    check('модуль: функции в подсказках', offeredNames.includes('Lib.hello'), offeredNames.slice(0, 6));

    const linkDir = path.join(require('os').homedir(), '.xerocode');
    const linkFile = path.join(linkDir, 'link.json');
    const realLink = fs.existsSync(linkFile) ? fs.readFileSync(linkFile) : null;
    const asked = [];
    const fake = require('http').createServer((req, res) => {
      const chunks = [];
      req.on('data', c => chunks.push(c));
      req.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        asked.push({ method: req.method, url: req.url, token: req.headers['x-xero-token'], body });
        const answer = req.url === '/status' ? { ok: true, online: true, dev: true, world: 'abcd1234' }
          : req.url === '/world' ? { ok: true, handlers: [{ type: 'event', event: 'player_join', position: 0, operations: [] }] }
          : { ok: true, done: true, url: 'http://x', confirmed: true, answers: ['Модуль установлен'] };
        res.setHeader('Content-Type', 'application/json');
        res.end(JSON.stringify(answer));
      });
    });
    await new Promise(r => fake.listen(0, '127.0.0.1', r));
    try {
      fs.mkdirSync(linkDir, { recursive: true });
      fs.writeFileSync(linkFile, JSON.stringify({ port: fake.address().port, token: 'tok' }), 'utf8');
      await vscode.env.clipboard.writeText('до заливки');
      await vscode.window.showTextDocument(fine);
      await vscode.commands.executeCommand('xero.upload');
      const sent = asked.find(x => x.method === 'POST');
      check('заливка через игру: код ушёл в мод', sent && sent.url === '/upload?mode=replace' && sent.token === 'tok'
        && JSON.parse(sent.body).handlers.length === 1, JSON.stringify(asked.map(x => x.url)));
      check('заливка через игру: буфер не тронут', await vscode.env.clipboard.readText() === 'до заливки');
      check('заливка через игру: копия мира записана', fs.existsSync(path.join(root, '.xero', 'world.json')));
      await vscode.commands.executeCommand('xero.add');
      check('дописать через игру', asked.some(x => x.url === '/upload?mode=add'), JSON.stringify(asked.map(x => x.url)));
      await vscode.commands.executeCommand('xero.pullGame');
      check('код мира из игры', asked.some(x => x.url === '/world') && fs.existsSync(path.join(root, 'world.xero'))
        && fs.readFileSync(path.join(root, 'world.xero'), 'utf8').includes('when player_join'), JSON.stringify(asked.map(x => x.url)));
    } finally {
      fake.close();
      if (realLink) fs.writeFileSync(linkFile, realLink);
      else fs.rmSync(linkFile, { force: true });
    }

    if (process.env.XERO_UPLOAD) {
      await vscode.window.showTextDocument(fine);
      await vscode.commands.executeCommand('xero.add');
      const clip = await vscode.env.clipboard.readText();
      check('команда «Дописать в мир»: команда в буфере', /^\/module loadUrl http:\/\/2\.27\.249\.33:8791\/xerocode\/code\/[0-9a-f]{32}\.json$/.test(clip), clip);
      report.push({ name: 'clipboard', ok: true, detail: clip });
    }
  } catch (e) {
    check('без исключений', false, e && e.stack ? e.stack : e);
  }
  fs.writeFileSync(out, JSON.stringify(report, null, 1), 'utf8');
  if (report.some(r => !r.ok)) throw new Error('проверки не прошли');
}

module.exports = { run };
