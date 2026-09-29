#!/usr/bin/env python3
"""Create one offline, phone-friendly HTML view of the actual project sources."""
from pathlib import Path
import html
import json
import re
import sys

root = Path(__file__).resolve().parent.parent
output = Path(sys.argv[1]) if len(sys.argv) > 1 else root.parent / 'BerryBoy-Source.html'
paths = [root / 'README.md', root / 'pom.xml']
paths += sorted((root / 'src/main/java').rglob('*.java'))
paths += sorted((root / 'platform').rglob('*.java'))
paths += sorted((root / 'src/test/java').rglob('*.java'))
paths += sorted((root / 'docs').glob('*.md'))
paths += [root / 'test.sh']
paths += sorted((root / 'tools').glob('*.sh'))
paths += [root / 'tools/build_source_view.py']
files = [{'path': str(p.relative_to(root)), 'name': p.name, 'text': p.read_text()} for p in paths]
keywords = r'\b(?:public|private|protected|static|final|class|record|sealed|interface|permits|abstract|break|continue|yield|void|var|int|long|boolean|byte|short|char|return|if|else|switch|case|default|new|for|while|try|catch|finally|throw|throws|import|package|extends|implements|true|false|null|instanceof|synchronized)\b'
token = re.compile(r'//[^\n]*|"(?:\\.|[^"\\])*"|' + keywords + r'|\b(?:0x[0-9a-fA-F]+|\d+)\b')
def paint(line, java):
    if not java:
        return html.escape(line)
    parts = []
    start = 0
    for match in token.finditer(line):
        parts.append(html.escape(line[start:match.start()]))
        value = match.group()
        kind = 'comment' if value.startswith('//') else 'string' if value.startswith('"') else 'number' if value[0].isdigit() else 'keyword'
        parts.append('<b class="' + kind + '">' + html.escape(value) + '</b>')
        start = match.end()
    return ''.join(parts) + html.escape(line[start:])
articles = []
for i, file in enumerate(files):
    lines = file['text'].splitlines()
    body = ''.join(f'<span class="line" data-n="{n}" id="f{i}l{n}">{paint(line, file["path"].endswith(".java")) or " "}</span>' for n, line in enumerate(lines, 1))
    articles.append(f'<article class="file" id="file{i}"><details open><summary>{html.escape(file["name"])} <small>{len(lines)} wierszy</small></summary><p class="path">{html.escape(file["path"])}</p><pre><code>{body}</code></pre></details></article>')
options = ''.join(f'<option value="{i}">{html.escape(f["name"])} — {html.escape(f["path"].rsplit("/",1)[0] if "/" in f["path"] else "projekt")}</option>' for i,f in enumerate(files))
data = json.dumps(files, ensure_ascii=False).replace('<', '\\u003c').replace('>', '\\u003e').replace('&', '\\u0026')
page = r'''<!doctype html><html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BerryBoy — kod źródłowy</title>
<style>
.shortcuts{display:flex;overflow-x:auto;gap:7px;margin-bottom:7px;scrollbar-width:thin}.shortcuts button{flex:0 0 auto;min-height:36px;padding:5px 11px}.shortcuts button[aria-pressed="true"]{color:#9ff4d7;background:#244b45;border-color:#6ad6b2}
:root{--code-size:13px;color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#10141e;color:#e2e8f0;font:15px/1.5 system-ui,-apple-system,sans-serif}header{padding:20px 16px 12px;max-width:1050px;margin:auto}h1{font-size:24px;letter-spacing:-.8px;margin:0 0 4px}header p{margin:4px 0;color:#aebbd2;font-size:13px}.tag{color:#75e1c4}.controls{position:sticky;top:0;z-index:5;background:#171e2ef5;border-top:1px solid #29344b;border-bottom:1px solid #36415b;padding:10px 12px;backdrop-filter:blur(10px)}.control-inner{max-width:1020px;margin:auto}.row{display:flex;gap:7px;margin-bottom:7px}select,input,button{font:inherit;border:1px solid #41516c;background:#202a3e;color:#edf4ff;border-radius:8px;min-height:42px;min-width:0;padding:7px 9px}select,input{width:100%}input{font-size:16px}button{cursor:pointer;white-space:nowrap;font-size:13px}button:hover{background:#30415c}button:focus-visible,select:focus-visible,input:focus-visible{outline:2px solid #76e4c7}#method{flex:1;font-size:13px}.grow{flex:1}#status{overflow-wrap:anywhere;font-size:12px;color:#b7c5de;min-height:18px}main{max-width:1050px;margin:auto;padding:12px 8px 80px}.file{background:#171e2e;border:1px solid #2b3850;border-radius:12px;margin-bottom:14px;overflow:hidden}.file[hidden]{display:none}summary{font-weight:650;padding:13px;cursor:pointer;overflow-wrap:anywhere}small{font-weight:400;color:#91a3bf;font-size:12px;margin-left:8px}.path{font-size:11px;color:#9eaec8;overflow-wrap:anywhere;padding:0 13px;margin-top:0}pre{margin:0 0 10px;overflow-x:auto;font:var(--code-size)/1.65 ui-monospace,SFMono-Regular,Consolas,monospace;tab-size:4}code{display:block}.line{display:block;position:relative;padding:0 10px 0 4.1em;min-height:1.65em;white-space:pre-wrap;overflow-wrap:anywhere}.line:before{content:attr(data-n);position:absolute;left:0;width:3.3em;text-align:right;color:#73829b;user-select:none}.nowrap .line{white-space:pre;overflow-wrap:normal}.line.match{background:#493e24}.line.current{background:#38482e;box-shadow:inset 3px 0 #9add71}b{font-weight:400}.keyword{color:#b5a0ff}.string{color:#96e0bc}.comment{color:#8e9eaf}.number{color:#ffbf85}footer{padding:12px;color:#91a3bf;font-size:12px}noscript p{margin:12px;padding:12px;background:#26334c}.controls{display:none}.js .controls{display:block}@media(min-width:800px){header{padding-top:32px}h1{font-size:30px}main{padding:20px 16px}pre{--code-size:14px}}@media(max-width:370px){button{padding:7px 6px;font-size:12px}.row{gap:4px}}
</style></head><body><header><h1>BerryBoy Advance</h1><p class="tag">Kod źródłowy · Java 21 · Linux · Maven · JUnit 5 · 0.4</p><p>Otwierasz testy JUnit 5. Skróty prowadzą do CLI i rdzenia emulatora. Podgląd wszystkich źródeł działa offline.</p></header>
<nav class="controls" aria-label="Przeglądanie kodu"><div class="control-inner"><div id="shortcuts" class="shortcuts" aria-label="Główne klasy"></div><div class="row"><select id="files" aria-label="Plik">__OPTIONS__</select></div><div class="row"><input id="search" type="search" placeholder="Szukaj w pliku…" aria-label="Szukaj w pliku"><button id="prev" aria-label="Poprzednie trafienie">↑</button><button id="next" aria-label="Następne trafienie">↓</button></div><div class="row"><button id="smaller" aria-label="Zmniejsz tekst">A−</button><button id="larger" aria-label="Powiększ tekst">A+</button><button id="wrap" aria-pressed="true">Zawijanie: tak</button><select id="method" aria-label="Przejdź do metody"><option value="">Metoda…</option></select><button id="download" aria-label="Pobierz wybrany plik">↓ plik</button></div><div id="status" role="status"></div></div></nav>
<noscript><p>JavaScript wyłączony: wszystkie pliki są poniżej. Rozwijaj sekcje i używaj wyszukiwania przeglądarki.</p></noscript><main>__ARTICLES__</main><footer>860 testów JUnit 5 na JDK 21. Maven verify: sukces. 834 testy powtórzone na wyeksportowanym rdzeniu. Runtime BB przez Wine wymaga jeszcze weryfikacji.</footer>
<script type="application/json" id="source-data">__DATA__</script><script>
'use strict';
const files=JSON.parse(document.getElementById('source-data').textContent);
const selector=document.getElementById('files'), search=document.getElementById('search'), method=document.getElementById('method'), status=document.getElementById('status');
let selected=files.findIndex(f=>f.name==='ArmArithmeticTest.java'), fontSize=13, matches=[], matchIndex=-1;
document.documentElement.classList.add('js');
const shortcuts = [
 ['JUnit', 'ArmArithmeticTest.java'], ['CLI', 'LaunchCommand.java'],
 ['CPU', 'Cpu.java'], ['ARM', 'ArmInterpreter.java'], ['Thumb', 'ThumbInterpreter.java'],
 ['Rejestry', 'CpuState.java'], ['ALU', 'ArithmeticLogicUnit.java'],
 ['Pamięć', 'Memory.java'], ['Eksport', 'LegacyExport.java']
];
shortcuts.forEach(([label, name]) => {
 const button = document.createElement('button');
 button.textContent = label;
 button.dataset.file = String(files.findIndex(file => file.name === name));
 button.onclick = () => { selector.value = button.dataset.file; showFile(); };
 document.getElementById('shortcuts').append(button);
});
function currentArticle(){return document.getElementById('file'+selected)}
function showFile(){
 selected=Number(selector.value);document.querySelectorAll('.file').forEach((a,i)=>a.hidden=i!==selected);
 document.querySelectorAll('#shortcuts button').forEach(button => {
   button.setAttribute('aria-pressed', String(Number(button.dataset.file) === selected));
 });
 const declaration = /^\s*(?:@\w+\s+)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|strictfp)\s+)*(?:[\w.$]+(?:<[^;={}]*>)?(?:\[\])*\s+)?(\w+)\s*\([^;{}]*\)\s*(?:throws\s+[^{]+)?\{/;
 const controlWords = ['if', 'for', 'while', 'switch', 'catch', 'synchronized'];
 method.replaceChildren(new Option('Metoda…',''));
 const lines = files[selected].text.split('\n');
 if (files[selected].name.endsWith('.java')) lines.forEach((line, index) => {
   if (!line.trim() || /^\s*(?:\/\/|\*|@)/.test(line)) return;
   const signature = lines.slice(index, index + 4).join('\n');
   const match = signature.match(declaration);
   if (match && !controlWords.includes(match[1])) {
     method.add(new Option(match[1] + ' · ' + (index + 1), String(index + 1)));
   }
 });
 currentArticle().querySelector('details').open=true;find();window.scrollTo({top:0,behavior:'auto'});
}
function find(){
 document.querySelectorAll('.match,.current').forEach(n=>n.classList.remove('match','current'));
 const q=search.value.toLocaleLowerCase();matches=[];matchIndex=-1;
 if(q)currentArticle().querySelectorAll('.line').forEach(n=>{if(n.textContent.toLocaleLowerCase().includes(q)){n.classList.add('match');matches.push(n)}});
 status.textContent=q?matches.length+' pasujących wierszy w pliku':files[selected].path;
}
function jump(line){const top=line.getBoundingClientRect().top+window.scrollY-document.querySelector('.controls').offsetHeight-14;window.scrollTo({top,behavior:'smooth'})}
function step(direction){if(!matches.length)return;matches.forEach(n=>n.classList.remove('current'));matchIndex=(matchIndex+direction+matches.length)%matches.length;const line=matches[matchIndex];line.classList.add('current');jump(line);status.textContent=(matchIndex+1)+' / '+matches.length+' · wiersz '+line.dataset.n}
selector.value=String(selected);selector.addEventListener('change',showFile);search.addEventListener('input',find);search.addEventListener('keydown',e=>{if(e.key==='Enter')step(e.shiftKey?-1:1)});
document.getElementById('next').onclick=()=>step(1);document.getElementById('prev').onclick=()=>step(-1);
method.onchange=()=>{if(method.value)jump(document.getElementById('f'+selected+'l'+method.value))};
function size(delta){fontSize=Math.max(10,Math.min(24,fontSize+delta));document.documentElement.style.setProperty('--code-size',fontSize+'px');document.querySelectorAll('pre').forEach(p=>p.style.setProperty('--code-size',fontSize+'px'))}
document.getElementById('smaller').onclick=()=>size(-1);document.getElementById('larger').onclick=()=>size(1);
document.getElementById('wrap').onclick=e=>{const nowrap=document.body.classList.toggle('nowrap');e.target.textContent='Zawijanie: '+(nowrap?'nie':'tak');e.target.setAttribute('aria-pressed',String(!nowrap))};
document.getElementById('download').onclick=()=>{const file=files[selected],url=URL.createObjectURL(new Blob([file.text],{type:'text/plain;charset=utf-8'})),a=document.createElement('a');a.href=url;a.download=file.name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)};
showFile();
</script></body></html>'''
values = {'OPTIONS': options, 'ARTICLES': ''.join(articles), 'DATA': data}
page = re.sub(r'__(OPTIONS|ARTICLES|DATA)__', lambda match: values[match[1]], page)
output.write_text(page)
print(f'{output}: {len(files)} files, {len(page.encode()):,} bytes')
