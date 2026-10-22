// @ts-nocheck
import {t, ui, uiLocale} from './i18n';
import DOMPurify from 'dompurify';
import {marked} from 'marked';

// Message presentation for the JetBrains conversation pane.
export const escapeMessageHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function formatGenerationElapsed(milliseconds){const totalSeconds=Math.max(0,Math.floor((Number(milliseconds)||0)/1000)),seconds=totalSeconds%60,minutes=Math.floor(totalSeconds/60)%60,hours=Math.floor(totalSeconds/3600),parts=[];if(hours)parts.push(ui`${hours} 小时`);if(hours||minutes)parts.push(ui`${minutes} 分`);parts.push(ui`${seconds} 秒`);return parts.join(' ');}
export function createConversationRenderer(context: {
  icon: (name: string) => string;
  commandDetails: () => Record<string, boolean>;
  showThinking: () => boolean;
}) {
  const icon = context.icon, esc = escapeMessageHtml, attr = escapeMessageHtml;
  const state = {get commandDetails(){return context.commandDetails();}, get showThinking(){return context.showThinking();}};
  function localFileLink(target){
    if(!target||/^https?:\/\//i.test(target))return null;
    let decodedTarget=target;
    try{decodedTarget=decodeURIComponent(target);}catch(_){}
    decodedTarget=decodedTarget.replace(/\\([`*_{}\[\]()#+.!|>~:-])/g,'$1').trim();
    // file URI 会被 DOMPurify 清除 href，先转换成普通本地路径再进入清洗流程。
    if(/^file:\/\//i.test(decodedTarget)){
      try{const url=new URL(decodedTarget);decodedTarget=`${url.host?`//${url.host}`:''}${decodeURIComponent(url.pathname)}`;}catch(_){return null;}
      if(/^\/[A-Za-z]:[\\/]/.test(decodedTarget))decodedTarget=decodedTarget.slice(1);
    }
    const location=/^(.+?)(?::(\d+)(?::(\d+))?)?$/.exec(decodedTarget),filePath=location?.[1]||'';
    if(!location||(!/[\\/]/.test(filePath)&&!/\.[^\\/\s]+$/.test(filePath)))return null;
    const line=location[2]?Number(location[2]):1,column=location[3]?Number(location[3]):1;
    if(!Number.isInteger(line)||line<1||!Number.isInteger(column)||column<1)return null;
    return{path:filePath,line,column};
  }
  function markdown(text) {
    const rendered=marked.parse(String(text||''),{gfm:true,breaks:true,async:false});
    const source=document.createElement('template'),localLinks=new Map();
    source.innerHTML=String(rendered);
    source.content.querySelectorAll('[data-codex-local-link]').forEach(element=>element.removeAttribute('data-codex-local-link'));
    source.content.querySelectorAll('a').forEach(link=>{
      const location=localFileLink(link.getAttribute('href')||'');
      if(!location)return;
      const id=String(localLinks.size);localLinks.set(id,location);link.dataset.codexLocalLink=id;
    });
    const template=document.createElement('template');
    template.innerHTML=DOMPurify.sanitize(source.innerHTML,{USE_PROFILES:{html:true}});
    // 仅使用清洗前由本地代码生成的临时 ID，Provider 自带 data 属性已统一移除。
    template.content.querySelectorAll('a').forEach(link=>{
      const id=link.dataset.codexLocalLink,location=id===undefined?null:localLinks.get(id);
      link.removeAttribute('data-codex-local-link');
      if(!location)return;
      link.removeAttribute('href');link.classList.add('file-link');link.dataset.filePath=location.path;link.dataset.fileLine=String(location.line);link.dataset.fileColumn=String(location.column);
    });
    template.content.querySelectorAll('pre > code').forEach(code=>{
      const pre=code.parentElement;if(!pre)return;
      const wrapper=document.createElement('div');wrapper.className='code-block';
      const language=[...code.classList].find(name=>name.startsWith('language-'))?.slice(9)||'text';
      const label=document.createElement('div');label.className='code-lang';label.textContent=language;
      const button=document.createElement('button');button.className='copy-code';button.dataset.copy=code.textContent||'';button.title=t('复制');button.innerHTML=icon('copy');
      pre.replaceWith(wrapper);wrapper.append(label,button,pre);
    });
    return `<div class="markdown">${template.innerHTML}</div>`;
  }

  function commandStatus(entry){
    const body=String(entry?.body||''),exitCode=entry?.exitCode!==undefined&&entry?.exitCode!==null?[null,String(entry.exitCode)]:body.match(/(?:退出码：|Exit code:)\s*(-?\d+)/i),reportedStatus=entry?.status||(body.match(/(?:执行状态：|Execution status:)\s*([^\r\n]+)/i)||[])[1]||'';
    const status=exitCode?(Number(exitCode[1])===0?'success':'failed'):/fail|error|cancel/i.test(reportedStatus)?'failed':/complete|success/i.test(reportedStatus)?'success':'running';
    return {kind:status,text:status==='success'?t('执行成功'):status==='failed'?t('执行失败'):t('执行中')};
  }

  function commandPresentation(entry,index){
    const body=String(entry.body||''),command=(body.split(/\r?\n/,1)[0]||'').replace(/^\$\s*/, '').trim()||t('等待命令');
    return {body,preview:command.length>64?command.slice(0,61)+'...':command,status:commandStatus(entry),key:entry.itemId||String(index),label:['Claude 工具','Claude tool'].includes(entry.title)?t('调用工具'):t('执行命令')};
  }

  function commandBatchStatusView(batch){
    const statuses=batch.map(commandStatus),failed=statuses.filter(item=>item.kind==='failed').length,running=statuses.filter(item=>item.kind==='running').length;
    if(failed)return ui`<span class="command-batch-status failed">${failed} 条失败</span>`;
    if(running)return t('<span class="command-batch-status running">执行中</span>');
    return ui`<span class="command-batch-status success">${icon('check')}全部完成</span>`;
  }

  function commandView(entry,index){
    const view=commandPresentation(entry,index),expanded=!!state.commandDetails[view.key];
    return `<article class="message tool command${expanded?' expanded':''}" data-message="${index}" data-item-id="${attr(entry.itemId||'')}"><button class="command-row" data-command-toggle="${attr(view.key)}" title="${expanded?t('收起详细指令'):t('展开详细指令')}" aria-expanded="${expanded}"><span class="command-label">${view.label}</span><code>${esc(view.preview)}</code><span class="command-status ${view.status.kind}" title="${view.status.text}" aria-label="${view.status.text}"></span></button>${expanded?`<pre class="command-details">${esc(view.body)}</pre>`:''}</article>`;
  }

  function messageView(entry,index) {
    const kind=entry.kind||'assistant';
    const identity=`data-message="${index}" data-item-id="${attr(entry.itemId||'')}"`;
    const meta=messageMeta(entry),copy=messageCopyButton(index);
    if(kind==='user')return `<article class="message user" ${identity}><div class="message-content">${meta}<div class="message-body user-message-body" data-user-message>${userMessageBody(entry)}</div><div class="message-footer">${copy}</div></div></article>`;
    if(kind==='assistant'){
      // AI 消息将时间与复制操作合并到头部，避免短回复生成独立底栏。
      const elapsed=Number(entry.elapsedMs),duration=Number.isFinite(elapsed)&&elapsed>=0?ui`<div class="assistant-duration">回复用时：${esc(formatGenerationElapsed(elapsed))}</div>`:'';
      return `<article class="message assistant" ${identity}><div class="message-content"><div class="message-header">${meta}${copy}</div><div class="message-body">${markdown(entry.body)}</div>${duration}</div></article>`;
    }
    if(kind==='reasoning')return state.showThinking?ui`<article class="message tool reasoning" ${identity}><div class="tool-card"><div class="tool-head"><span class="tool-kind">思考过程</span></div><div class="tool-body">${esc(entry.body)}</div></div></article>`:'';
    if(kind==='notice')return `<article class="message notice" ${identity}>${esc(entry.title)}${entry.title&&entry.body?' · ':''}${esc(entry.body)}</article>`;
    if(kind==='command'){
      return commandView(entry,index);
    }
    const names={plan:t('计划'),mcp:t('MCP 工具'),error:t('错误')};
    return `<article class="message tool ${kind}" ${identity}><div class="tool-card"><div class="tool-head"><span class="tool-kind">${esc(names[kind]||entry.title||'Codex')}</span><span>${esc(entry.title&&entry.title!==names[kind]?entry.title:'')}</span></div><div class="tool-body">${esc(entry.body)}</div></div></article>`;
  }

  function messageMeta(entry){
    const value=Number(entry?.createdAtEpochMs),date=Number.isFinite(value)&&value>0?new Date(value):null;
    if(!date||Number.isNaN(date.getTime()))return '';
    const label=new Intl.DateTimeFormat(uiLocale(),{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(date);
    const detail=new Intl.DateTimeFormat(uiLocale(),{dateStyle:'long',timeStyle:'medium',hour12:false}).format(date);
    return `<div class="message-meta"><time datetime="${attr(date.toISOString())}" title="${attr(detail)}">${esc(label)}</time></div>`;
  }

  function messageCopyButton(index){
    return ui`<button class="message-copy" data-copy-message="${index}" title="复制消息" aria-label="复制消息">${icon('copy')}</button>`;
  }

  function userMessageBody(entry){
    const body=String(entry?.body||''),paths=Array.isArray(entry?.fileReferencePaths)?entry.fileReferencePaths.filter(path=>String(path||'')):[];
    if(!paths.length)return esc(body);
    let result='',cursor=0;
    for(const path of paths){
      const token=`@${path}`,index=body.indexOf(token,cursor);
      if(index<0)continue;
      result+=esc(body.slice(cursor,index));
      result+=`<span class="message-file-reference" data-file-reference-path="${attr(path)}">${esc(token)}</span>`;
      cursor=index+token.length;
    }
    return result+esc(body.slice(cursor));
  }

  return {markdown, messageView, commandView, commandStatus, commandPresentation, commandBatchStatusView};
}
