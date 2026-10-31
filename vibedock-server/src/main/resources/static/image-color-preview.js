(() => {
 const $=id=>document.getElementById(id),grid=$('color-preview-grid');if(!grid)return;
 let mode='auto',cards=[],generation=0;
 const fmt=n=>new Intl.NumberFormat().format(n),esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 function draw(){
  if(cards.length)grid.style.setProperty('--color-card-width',(cards[0].width+26)+'px');
  grid.innerHTML=cards.map(c=>{
   const lossless=c.changed===0,dir=c.used&&c.base&&c.used!==c.base?(c.used>c.base?'↑':'↓'):c.base&&c.budget&&c.budget!==c.base?(c.budget>c.base?'↑':'↓'):'';
   const tooltip=dir==='↑'?'Quality fallback: one higher palette':dir==='↓'?(lossless?'Fewer colors, pixel-identical':'Automatic smaller palette'):'Configured palette';
   const badge=c.used?`${c.used}${dir?' '+dir:''}`:'RGB';
   const label=c.used?c.bits+' bit':'Full RGB565';
   return `<article class="card" data-theme="${esc(c.id)}"><h3><span class="color-title">${esc(c.name)}</span><span class="color-palette" title="${esc(tooltip)}" aria-label="${esc(tooltip)}">${badge}</span></h3><div class="color-test-image"><img src="${c.image}" width="${c.width}" height="${c.height}" alt="${esc(c.name)}" style="width:${c.width}px;height:${c.height}px"></div><small>${label} · ${c.width}×${c.height}<br>${c.changed.toFixed(2)}% pixels changed${lossless?' · lossless':''}<br>PNG ${fmt(c.pngBytes)} B · ${$('color-display').value==='web'?'PNG transfer':'device payload'} ${fmt(c.payloadBytes)} B<br>${(100*(1-c.payloadBytes/c.originalPayload)).toFixed(1)}% fewer payload bytes</small></article>`;
  }).join('');
 }
 async function load(){
  const own=++generation;$('color-preview-status').textContent='Rendering current data…';
  try{const q=new URLSearchParams({display:$('color-display').value,mode,fallback:String($('color-fallback').checked),source:$('color-source').value==='live'?'live':'reference',state:$('color-source').value==='live'?'0':$('color-source').value.slice(-1)}),r=await fetch('/admin/color-preview/data?'+q,{cache:'no-store'});if(!r.ok)throw Error('HTTP '+r.status);const data=await r.json();if(own!==generation)return;cards=data.cards;draw();$('color-preview-status').textContent=cards.length+' themes · '+(cards[0]?cards[0].width+'×'+cards[0].height:'')+' · '+($('color-source').value==='live'?'current server data':'reference data')+(data.calibrating?' · calibrating automatic quality…':'');if(data.calibrating&&mode==='auto'&&$('color-fallback').checked)waitForCalibration(own);}
  catch(e){if(own===generation)$('color-preview-status').textContent='Could not load comparison: '+e.message;}
 }
 async function waitForCalibration(own){setTimeout(async()=>{if(own!==generation||document.hidden)return;try{const s=await(await fetch('/admin/color-preview/quality')).json();if(s.calibrating)waitForCalibration(own);else load()}catch{}},1500)}
 $('color-source').onchange=load;
 $('color-display').onchange=()=>{mode=$('color-display').value==='web'?'lossless':'auto';buttons();load()};$('color-fallback').onchange=load;$('color-refresh').onclick=load;
 function buttons(){$('color-fallback').disabled=$('color-display').value==='web';for(const b of $('color-modes').querySelectorAll('button')){b.classList.toggle('secondary',b.dataset.colorMode!==mode);b.disabled=$('color-display').value==='web'&&['32','64','256'].includes(b.dataset.colorMode)}}
 const initial=new URLSearchParams(location.search),source=initial.get('source');if(['live','reference0','reference1','reference2'].includes(source))$('color-source').value=source;
 $('color-modes').onclick=e=>{const b=e.target.closest('[data-color-mode]');if(b){mode=b.dataset.colorMode;buttons();load()}};load();
})();
