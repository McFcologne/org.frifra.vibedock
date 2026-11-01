(() => {
  const body=document.getElementById('image-color-rules'),add=document.getElementById('image-color-add');
  if(!body||!add)return;
  const changed=()=>body.closest('form').dispatchEvent(new Event('input',{bubbles:true}));
  add.addEventListener('click',()=>{
    if(body.rows.length>=16)return;
    const row=body.insertRow();
    row.innerHTML='<td><input aria-label="Maximum width" name="maxWidth" type="number" min="16" max="4096" required value="640"></td><td><input aria-label="Maximum height" name="maxHeight" type="number" min="16" max="4096" required value="640"></td><td><select aria-label="Colors" name="colors"><option value="32">32</option><option value="64">64</option><option value="256" selected>256</option><option value="0">Full colors</option></select></td><td><button type="button" class="secondary" data-color-up aria-label="Move rule up">↑</button> <button type="button" class="secondary" data-color-down aria-label="Move rule down">↓</button> <button type="button" class="secondary" data-color-remove aria-label="Remove rule">×</button></td>';
    row.querySelector('input').focus();changed();
  });
  body.addEventListener('click',event=>{
    const button=event.target.closest('button'),row=button?.closest('tr');if(!row)return;
    if(button.hasAttribute('data-color-remove'))row.remove();
    else if(button.hasAttribute('data-color-up')&&row.previousElementSibling)body.insertBefore(row,row.previousElementSibling);
    else if(button.hasAttribute('data-color-down')&&row.nextElementSibling)body.insertBefore(row.nextElementSibling,row);
    changed();
  });
})();
