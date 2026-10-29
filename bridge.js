/* Native bridge: inside the Android/iOS app, saving a file (export, SRT, GIF, MP3) opens the system Share sheet / saves to the phone. In a normal browser this file does nothing. */
(function(){
  var C=window.Capacitor;if(!C||!C.isNativePlatform||!C.isNativePlatform())return;
  var P=C.Plugins||{};
  var CH=3*1024*1024*3; /* 9 MB per piece: a multiple of 3 bytes, so the pieces join as valid base64 */
  function b64(blob){return new Promise(function(res,rej){var fr=new FileReader();fr.onload=function(){res(String(fr.result).split(',')[1]||'')};fr.onerror=function(){rej(fr.error)};fr.readAsDataURL(blob)})}
  /* big files are written piece by piece (low memory, no giant base64 string); the next piece is read while the previous one is being written */
  function writeAll(name,b){
    var F=P.Filesystem;
    if(!F.appendFile||b.size<=CH)return b64(b).then(function(d){return F.writeFile({path:name,data:d,directory:'CACHE'})});
    var pos=0,nextRead=b64(b.slice(0,CH));
    function step(){
      return nextRead.then(function(d){
        var at=pos;pos+=CH;
        nextRead=pos<b.size?b64(b.slice(pos,pos+CH)):null;
        var w=at===0?F.writeFile({path:name,data:d,directory:'CACHE'}):F.appendFile({path:name,data:d,directory:'CACHE'});
        return w.then(function(){return nextRead?step():null});
      });
    }
    return step().then(function(){return F.getUri({path:name,directory:'CACHE'})});
  }
  function nativeSave(a){
    var name=a.download||'onelly-edit';
    fetch(a.href).then(function(r){return r.blob()}).then(function(b){
      return writeAll(name,b);
    }).then(function(f){
      return P.Share.share({title:name,url:f.uri,dialogTitle:name});
    }).catch(function(e){if(!e||!/cancel/i.test(String(e.message||e)))alert('Save failed')});
  }
  try{Object.defineProperty(navigator,'canShare',{value:function(){return false},configurable:true})}catch(e){}
  var oc=HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click=function(){if(this.download&&/^blob:/.test(this.href)){nativeSave(this);return}oc.call(this)};
  document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[download]');if(a&&/^blob:/.test(a.href)){e.preventDefault();e.stopPropagation();nativeSave(a)}},true);
})();
