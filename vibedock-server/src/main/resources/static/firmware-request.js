const form=document.querySelector("[data-firmware-request]");
if(form)(function(){
  "use strict";
  var error=form.querySelector("[data-request-error]"),state=form.querySelector("[data-request-state]"),file=form.querySelector("[data-request-photo]");
  function fail(text){error.textContent=text;error.hidden=!text;}
  /** The photo as JPEG of at most 1600 px as base64 (empty without a photo); the server checks it again. */
  function photo(){
    var f=file&&file.files[0];if(!f)return Promise.resolve("");
    return new Promise(function(resolve,reject){
      var url=URL.createObjectURL(f),img=new Image();
      img.onload=function(){
        var scale=Math.min(1,1600/Math.max(img.naturalWidth,img.naturalHeight)),c=document.createElement("canvas");
        c.width=Math.max(1,Math.round(img.naturalWidth*scale));c.height=Math.max(1,Math.round(img.naturalHeight*scale));
        c.getContext("2d").drawImage(img,0,0,c.width,c.height);URL.revokeObjectURL(url);
        resolve(c.toDataURL("image/jpeg",.88).split(",")[1]);
      };
      img.onerror=function(){URL.revokeObjectURL(url);reject(new Error("The browser cannot read this photo (JPEG, PNG, GIF or BMP; not HEIC)"));};
      img.src=url;
    });
  }
  form.addEventListener("submit",function(event){
    event.preventDefault();fail("");state.textContent="Preparing …";
    var answers={};Array.prototype.forEach.call(form.elements,function(e){if(e.name&&e.value)answers[e.name]=e.value;});
    var csrf=document.querySelector('meta[name="csrf-token"]'),headers={"Content-Type":"application/json"};
    if(csrf)headers[document.querySelector('meta[name="csrf-header"]').content]=csrf.content;
    photo().then(function(data){
      return fetch(form.getAttribute("action")||location.pathname,{method:"POST",headers:headers,body:JSON.stringify({answers:answers,photo:data})});
    }).then(function(response){
      if(!response.ok)return response.json().then(function(j){throw new Error(j.error||"The request was refused");},function(){throw new Error("The request was refused");});
      var name=(/filename="?([^";]+)"?/.exec(response.headers.get("Content-Disposition")||"")||[])[1]||"firmware-request.zip";
      return response.blob().then(function(blob){
        var a=document.createElement("a");a.href=URL.createObjectURL(blob);a.download=name;document.body.appendChild(a);a.click();a.remove();
        state.textContent="Saved as "+name;
      });
    }).catch(function(e){state.textContent="";fail(e.message);});
  });
  // Reading the board over USB with esptool-js (the same file the flasher uses): chip, flash size, features.
  var read=form.querySelector("[data-request-read]"),note=form.querySelector("[data-request-read-state]");
  if(read&&form.dataset.esptool&&"serial" in navigator&&window.isSecureContext){
    read.hidden=false;
    read.addEventListener("click",async function(){
      read.disabled=true;note.textContent="Connecting …";var transport;
      try{
        var module=await import(form.dataset.esptool),port=await navigator.serial.requestPort();
        transport=new module.Transport(port,true);
        var loader=new module.ESPLoader({transport:transport,baudrate:115200,terminal:{clean:function(){},writeLine:function(){},write:function(){}}});
        var chip=await loader.main(),lines=["Chip: "+chip],flash="";
        try{flash=await loader.detectFlashSize();if(flash)lines.push("Flash: "+flash);}catch(e){}
        try{lines.push("Features: "+(await loader.chip.getChipFeatures(loader)).join(", "));}catch(e){}
        try{lines.push("Crystal: "+(await loader.chip.getCrystalFreq(loader))+" MHz");}catch(e){}
        var family=/^ESP8266/i.test(chip)?"ESP8266":((/^ESP32-(S2|S3|C2|C3|C5|C6|H2|P4)\b/i.exec(chip)||[])[0]||(/^ESP32/i.test(chip)?"ESP32":"")).toUpperCase();
        var select=form.elements["board.chip"];if(family&&Array.prototype.some.call(select.options,function(o){return o.value===family;}))select.value=family;
        var mb=/^(\d+)MB$/.exec(flash||"");if(mb)form.elements["board.flashMb"].value=mb[1];
        form.querySelector("[data-request-usb]").value=lines.join("\n");
        try{await loader.after("hard_reset");}catch(e){}
        note.textContent="Read: "+chip;
      }catch(e){note.textContent=(e&&e.name==="NotFoundError")?"No port chosen.":"Reading failed: "+(e&&e.message||e);}
      finally{try{if(transport)await transport.disconnect();}catch(e){}read.disabled=false;}
    });
  }
})();
