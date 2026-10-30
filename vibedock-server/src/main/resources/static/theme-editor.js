/* Theme drafts stay in this page until an explicit save/export. No package content is executed. */
(() => {
  const root=document.querySelector('#theme-editor');if(!root)return;
  const $=id=>root.querySelector('#'+id), form=$('te-form'),controls=$('te-controls');
  const csrf=document.querySelector('meta[name="csrf-token"]').content,csrfHeader=document.querySelector('meta[name="csrf-header"]').content;
  let draft,originalIcons=new Map(),dirty=false,timer,revision=0,previewRequest,previewURL,busy=false;
  const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text)n.textContent=text;if(cls)n.className=cls;return n;};
  const label=(text,input)=>{const l=el('label',text);l.append(input);return l;};
  const names={modern:'Modern',terminal:'Terminal',mac:'Classic Mac',windows:'Classic Windows',fluent:'Windows 11',macos:'macOS',console:'Starship console',lcd:'Handheld LCD',blueprint:'Blueprint',neon:'Neon',print:'Newsprint',mission:'Mission Control',departure:'Departure Board',nordic:'Nordic',hifi:'Hi-Fi',solarized:'Solarized',quest:'Pixel Quest',bauhaus:'Bauhaus',ink:'E-Ink',industrial:'Industrial',porcelain:'Porcelain',github:'GitHub',code:'VS Code',shell:'Shell',diff:'Git Diff',debug:'Debugger',rect:'Rectangle',ellipse:'Ellipse',line:'Line',gradient:'Gradient',grid:'Grid'};
  const option=(value,text)=>{const n=el('option',text);n.value=value;return n;};
  const fail=message=>{$('te-error').textContent=message||'';$('te-error').hidden=!message;};
  async function response(url,body,signal){
    const r=await fetch(url,{method:body===undefined?'GET':'POST',credentials:'same-origin',signal,
      headers:body===undefined?{}:{'Content-Type':'application/json',[csrfHeader]:csrf},body:body===undefined?undefined:JSON.stringify(body)});
    if(!r.ok){const info=await r.json().catch(()=>({}));throw new Error(info.error||'Theme action failed ('+r.status+').');}return r;
  }
  function changed(){if(busy)return;dirty=true;revision++;$('te-saved').textContent='Unsaved changes';schedule();}
  function schedule(){
    clearTimeout(timer);previewRequest?.abort();$('te-image').hidden=true;$('te-status').textContent='Updating preview…';
    timer=setTimeout(preview,350);
  }
  async function preview(){
    if(!draft||!form.checkValidity()){$('te-status').textContent='Complete the highlighted fields to preview.';return;}
    const current=revision,controller=new AbortController();previewRequest=controller;
    try{
      const r=await response('/admin/theme-editor/preview.png?display='+encodeURIComponent($('te-display').value),draft,controller.signal);
      const blob=await r.blob();if(current!==revision||controller.signal.aborted)return;
      if(previewURL)URL.revokeObjectURL(previewURL);previewURL=URL.createObjectURL(blob);$('te-image').src=previewURL;$('te-image').hidden=false;
      $('te-status').textContent='Current draft · device renderer';fail('');
    }catch(e){if(e.name!=='AbortError'&&current===revision){$('te-status').textContent='Preview unavailable';fail(e.message);}}
  }
  function color(value){return value?.startsWith('@')?draft.drawing.palette[value.slice(1)]||'#000000':value||'#000000';}
  function optionalColor(property,prefix){
    const exists=draft.drawing[property]!==undefined;$('te-'+prefix+'-mode').value=exists?'custom':'auto';
    $('te-'+prefix).hidden=!exists;$('te-'+prefix).value=color(draft.drawing[property]);
  }
  function bars(){
    const fixed=['terminal','mac','windows','lcd','blueprint','print'].includes(draft.drawing.preset);
    $('te-bar-mode').disabled=fixed;$('te-bar').disabled=fixed;
    $('te-bar-note').textContent=fixed?'This style draws palette-based or text bars.':'';
  }
  function input(value,change,type='text'){
    const n=document.createElement('input');n.type=type;n.value=value;n.addEventListener('input',()=>{change(type==='number'?Number(n.value):n.value);changed();});return n;
  }
  function select(values,value,change){const n=document.createElement('select');values.forEach(v=>n.append(option(v,names[v]||v)));n.value=value;n.addEventListener('change',()=>change(n.value));return n;}
  function number(value,low,high,change,step='1'){const n=input(value,change,'number');n.min=low;n.max=high;n.step=step;n.required=true;return n;}
  function check(value,change){const n=document.createElement('input');n.type='checkbox';n.checked=value;n.addEventListener('change',()=>{change(n.checked);changed();});return n;}
  function button(text,action){const n=el('button',text,'secondary');n.type='button';n.addEventListener('click',action);return n;}
  function layers(){
    const container=$('te-layers');container.replaceChildren();
    for(const area of ['background','band']){
      const section=el('div',null,'te-layer');section.append(el('h4',area==='background'?'Whole image':'Each usage band'));
      const shapes=draft.drawing.layers?.[area]||[];
      shapes.forEach((shape,index)=>{
        const row=el('div',null,'te-shape'),head=el('div',null,'te-actions');head.append(el('strong',(index+1)+'. '+(names[shape.op]||shape.op)));
        const move=step=>{const other=index+step;[shapes[index],shapes[other]]=[shapes[other],shapes[index]];layers();changed();};
        const up=button('↑',()=>move(-1)),down=button('↓',()=>move(1));up.disabled=index===0;down.disabled=index===shapes.length-1;
        up.setAttribute('aria-label','Move decoration up');down.setAttribute('aria-label','Move decoration down');
        head.append(up,down,button('Remove decoration',()=>{shapes.splice(index,1);layers();changed();}));row.append(head);
        const fields=el('div',null,'te-fields');
        for(const key of ['x','y','width','height']){
          const value=shape[key],length=typeof value==='number'?{relative:0,pixels:value}:value;
          const pair=el('div',null,'te-length');
          const update=(part,v)=>{shape[key]={relative:length.relative,pixels:length.pixels,[part]:v};length[part]=v;};
          pair.append(label(key+' %',number(length.relative*100,-400,400,v=>update('relative',v/100),'any')),
            label(key+' px',number(length.pixels,-4096,4096,v=>update('pixels',v),'any')));fields.append(pair);
        }
        fields.append(label('Color',input(color(shape.color),v=>shape.color=v,'color')),
          label('Opacity',number(shape.opacity??1,0,1,v=>shape.opacity=v,'0.05')));
        if(['rect','ellipse'].includes(shape.op))fields.append(label('Filled',check(shape.fill??true,v=>shape.fill=v)));
        if(shape.op==='rect')fields.append(label('Corner radius',number(shape.radius??0,0,128,v=>shape.radius=v)));
        if(shape.op==='grid')fields.append(label('Grid spacing',number(shape.step??8,1,128,v=>shape.step=v)));
        if(shape.op==='gradient')fields.append(label('To color',input(color(shape.to),v=>shape.to=v,'color')),label('Vertical',check(shape.vertical??true,v=>shape.vertical=v)));
        row.append(fields);section.append(row);
      });
      const add=select(['rect','ellipse','line','gradient','grid'],'rect',()=>{});add.setAttribute('aria-label','New '+area+' decoration');
      const tools=el('div',null,'te-actions');tools.append(add,button('Add decoration',()=>{
        draft.drawing.layers??={};draft.drawing.layers[area]??=[];const list=draft.drawing.layers[area];
        if(list.length>=32){fail('At most 32 decorations per layer.');return;}
        const shape={op:add.value,x:0,y:0,width:{relative:1,pixels:0},height:{relative:1,pixels:0},color:'@track',opacity:0.2};
        if(shape.op==='rect'){shape.fill=false;shape.radius=8;}if(shape.op==='gradient')shape.to='@background';
        list.push(shape);layers();changed();
      }));section.append(tools);container.append(section);
    }
  }
  async function upload(file,existing){
    if(!file||busy)return;busy=true;controls.disabled=true;
    try{
      const ext=file.name.toLowerCase().endsWith('.svg')?'.svg':file.name.toLowerCase().endsWith('.png')?'.png':null;
      if(!ext||file.size>128*1024)throw new Error('Choose a PNG or safe SVG of at most 128 KiB.');
      const response=await fetch('/admin/theme-editor/icon?format='+ext.slice(1),{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/octet-stream',[csrfHeader]:csrf},body:file});
      const converted=await response.json();if(!response.ok)throw new Error(converted.error||'Could not prepare the icon.');
      const data=converted.data;
      $('te-upload-status').textContent=converted.converted?'SVG converted to a static PNG'+(converted.removed?' · '+converted.removed+' unsupported items removed. Check the preview; details may have changed.':'. Check the preview.'):'PNG validated.';
      if(existing){existing.file='uploaded.png';existing.data=data;}
      else{
        if(draft.icons.length>=32)throw new Error('A theme can have at most 32 icons.');
        let id='icon',n=1;while(draft.icons.some(i=>i.id===id))id='icon-'+n++;
        draft.icons.push({id,label:file.name.slice(0,80),file:'uploaded.png',data});
      }
      busy=false;icons();changed();
    }catch(e){fail(e.message||'Could not read icon.');}finally{busy=false;controls.disabled=false;}
  }
  function icons(){
    const list=$('te-icons');list.replaceChildren();
    const chosen=draft.icons.find(i=>i.id===draft.defaultIcon)||draft.icons[0];
    const extra=el('details'),extraList=el('div');extra.append(el('summary','Additional catalog icons ('+(draft.icons.length-1)+')'),
      el('p','Optional alternatives for the icon catalog. These are not displayed together with the default.','meta'),extraList);
    for(const icon of [chosen,...draft.icons.filter(i=>i!==chosen)]){
      const row=el('div',null,'te-icon'),fields=el('div',null,'te-fields');
      const id=input(icon.id,value=>{if(draft.defaultIcon===icon.id)draft.defaultIcon=value;icon.id=value;});id.pattern='[a-z][a-z0-9._\\-]{0,79}';id.maxLength=80;id.required=true;
      const name=input(icon.label,value=>icon.label=value);name.maxLength=80;name.required=true;
      const use=document.createElement('input');use.type='radio';use.name='default-icon';use.checked=draft.defaultIcon===icon.id;
      use.addEventListener('change',()=>{draft.defaultIcon=icon.id;icons();changed();});fields.append(label('Icon ID',id),label('Label',name));row.append(label('Theme default',use));
      row.prepend(el('h4',icon===chosen?'Default theme icon':'Catalog alternative'));row.append(el('p',icon.label));
      const original=originalIcons.get(icon.file);
      if(!icon.data&&original){const image=document.createElement('img');image.src='/admin/themes/'+encodeURIComponent(draft.sourceId)+'/icons/'+encodeURIComponent(original)+'.png';image.alt=icon.label;image.width=56;image.height=56;row.append(image);}
      if(icon.data&&icon.file.endsWith('.png')){const image=document.createElement('img');image.src='data:image/png;base64,'+icon.data;image.alt=icon.label;image.width=56;image.height=56;row.append(image);}
      const advanced=el('details');advanced.append(el('summary','Icon name and ID'),fields);row.append(advanced);
      const tools=el('div',null,'te-actions'),replace=document.createElement('input');replace.type='file';replace.accept='.png,.svg';replace.setAttribute('aria-label','Replace '+icon.label);
      replace.addEventListener('change',()=>upload(replace.files[0],icon));
      const remove=button('Remove icon',()=>{draft.icons.splice(draft.icons.indexOf(icon),1);if(draft.defaultIcon===icon.id)draft.defaultIcon=draft.icons[0].id;icons();changed();});remove.disabled=draft.icons.length===1;
      tools.append(label('Replace artwork',replace),remove);row.append(tools,el('p',icon.data?'New artwork · validated in preview':'Artwork: '+icon.file,'meta'));(icon===chosen?list:extraList).append(row);
    }
    extra.open=draft.icons.some(i=>i!==chosen&&i.data);list.append(extra);
  }
  async function open(id,copy=false){
    controls.disabled=true;const state=await(await response('/admin/themes/'+encodeURIComponent(id)+'/editor?copy='+copy)).json();draft=state.draft;originalIcons=new Map(draft.icons.map(i=>[i.file,i.id]));
    for(const key of ['name','id','version','author','license'])$('te-'+key).value=draft[key];
    $('te-preset').replaceChildren(...state.presets.map(v=>option(v,names[v]||v)));$('te-preset').value=draft.drawing.preset;
    $('te-font').value=draft.drawing.font;
    $('te-palette').replaceChildren();for(const [key,value] of Object.entries(draft.drawing.palette)){
      const control=input(value,v=>draft.drawing.palette[key]=v,'color');control.id='te-color-'+key;$('te-palette').append(label(key[0].toUpperCase()+key.slice(1),control));
    }
    $('te-display').replaceChildren(...state.displays.map(d=>option(d.id,d.label)));
    $('te-source').textContent=state.builtin?'Based on '+id+' · built-in original stays unchanged':'Based on '+id+' · save a newer version or choose a new ID for a copy';
    for(const [prop,prefix] of [['footerBackground','footer'],['barColor','bar']])optionalColor(prop,prefix);
    layers();icons();bars();controls.disabled=false;dirty=false;revision++;schedule();
  }
  for(const key of ['name','id','version','author','license'])$('te-'+key).addEventListener('input',()=>{draft[key]=$('te-'+key).value;changed();});
  $('te-font').addEventListener('change',()=>{draft.drawing.font=$('te-font').value;changed();});
  $('te-preset').addEventListener('change',()=>{
    draft.drawing.preset=$('te-preset').value;
    if(['terminal','mac','windows','lcd','blueprint','print'].includes(draft.drawing.preset)){delete draft.drawing.barColor;optionalColor('barColor','bar');}
    bars();changed();
  });
  for(const [prop,prefix] of [['footerBackground','footer'],['barColor','bar']]){
    $('te-'+prefix+'-mode').addEventListener('change',()=>{if($('te-'+prefix+'-mode').value==='auto')delete draft.drawing[prop];else draft.drawing[prop]=$('te-'+prefix).value;optionalColor(prop,prefix);changed();});
    $('te-'+prefix).addEventListener('input',()=>{draft.drawing[prop]=$('te-'+prefix).value;changed();});
  }
  $('te-display').addEventListener('change',()=>{revision++;schedule();});
  $('te-icon-add').addEventListener('change',async()=>{await upload($('te-icon-add').files[0]);$('te-icon-add').value='';});
  async function perform(save){
    if(busy||!form.reportValidity())return;busy=true;clearTimeout(timer);previewRequest?.abort();revision++;controls.disabled=true;fail('');
    try{
      const result=await response('/admin/theme-editor/'+(save?'save':'export?agent='+$('te-agent').checked),draft);
      if(save){
        const installed=await result.json();dirty=false;
        history.replaceState(null,'','/admin/themes/'+encodeURIComponent(installed.id)+'/edit');
        await open(installed.id);$('te-saved').textContent=installed.name+' v'+installed.version+' saved. Available in All themes.';
      }else{
        const url=URL.createObjectURL(await result.blob()),a=document.createElement('a');a.href=url;a.download=draft.id+'.vibetheme';document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
        $('te-saved').textContent='Draft exported. Installation unchanged.';schedule();
      }
    }catch(e){fail(e.message);}finally{busy=false;controls.disabled=!draft;}
  }
  form.addEventListener('submit',e=>{e.preventDefault();perform(true);});$('te-export').addEventListener('click',()=>perform(false));
  window.addEventListener('beforeunload',e=>{if(dirty){e.preventDefault();e.returnValue='';}});
  const id=decodeURIComponent(location.pathname.split('/')[3]);open(id,new URLSearchParams(location.search).get('copy')==='true').catch(e=>fail(e.message));
})();
