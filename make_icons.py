from PIL import Image, ImageDraw, ImageChops, ImageFilter, ImageFont
import os, math

def lerp(a,b,t): return tuple(int(a[i]+(b[i]-a[i])*t) for i in range(3))
def lin_grad(S,stops,diag=True):
    im=Image.new("RGB",(S,S)); px=im.load(); n=len(stops)-1
    for y in range(S):
        for x in range(S):
            t=(x+y)/(2*S-2) if diag else y/(S-1)
            i=min(int(t*n),n-1); px[x,y]=lerp(stops[i],stops[i+1],t*n-i)
    return im.convert("RGBA")

def render_bg(S):
    im=lin_grad(S,[(20,16,48),(11,11,28),(30,12,56)])
    c=S/2; R=S*.43
    sph=Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(sph)
    n=int(R)
    for i in range(n,0,-1):
        t=1-i/R   # 0 edge -> 1 centre
        col=lerp((70,150,245),(26,28,92),min(1,t*2.2))
        d.ellipse([c-i,c-i,c+i,c+i],fill=col+(255,))
    m=Image.new("L",(S,S),0); ImageDraw.Draw(m).ellipse([c-R,c-R,c+R,c+R],fill=255)
    land=Image.new("RGBA",(S,S),(0,0,0,0)); ld=ImageDraw.Draw(land)
    for x,y,rx,ry in [(-.45,-.25,.2,.28),(-.38,.18,.12,.3),(-.55,.05,.1,.15),(.2,-.22,.17,.2),(.27,.14,.12,.26),(.52,-.12,.16,.14),(.62,.2,.1,.08)]:
        ld.ellipse([c+(x-rx)*R,c+(y-ry)*R,c+(x+rx)*R,c+(y+ry)*R],fill=(70,220,180,95))
    sph.alpha_composite(land)
    sph.putalpha(ImageChops.multiply(sph.getchannel("A"),m))
    im.alpha_composite(sph)
    L=Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(L)
    w=max(2,int(S*.007)); col=(170,220,255,115)
    for k in (.35,.7): d.ellipse([c-R*k,c-R,c+R*k,c+R],outline=col,width=w)
    d.line([c,c-R,c,c+R],fill=col,width=w)
    for k in (-.55,0,.55):
        y=c+R*k; h=math.sqrt(max(0,R*R-(R*k)**2)); d.line([c-h,y,c+h,y],fill=col,width=w)
    d.ellipse([c-R,c-R,c+R,c+R],outline=(140,210,255,200),width=int(S*.012))
    im.alpha_composite(L)
    return im

from PIL import ImageFont
FONT="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
def render_fg(S):
    K=4; N=S*K; u=N/108
    out=Image.new("RGBA",(N,N),(0,0,0,0))
    cx,cy=54*u,36*u
    rx,ry,rot=27*u,8.5*u,22
    def orbit(front):
        lay=Image.new("RGBA",(N,N),(0,0,0,0)); d=ImageDraw.Draw(lay)
        d.ellipse([cx-rx,cy-ry,cx+rx,cy+ry],outline=(94,234,212,255),width=int(1.5*u))
        mask=Image.new("L",(N,N),0); m=ImageDraw.Draw(mask)
        if front: m.rectangle([0,cy,N,N],fill=255)
        else: m.rectangle([0,0,N,cy],fill=255)
        lay.putalpha(ImageChops.multiply(lay.getchannel("A"),mask))
        return lay.rotate(rot,center=(cx,cy),resample=Image.BICUBIC)
    out.alpha_composite(orbit(False))
    d=ImageDraw.Draw(out)
    r=16.5*u
    d.ellipse([cx-r,cy-r,cx+r,cy+r],outline=(255,255,255,255),width=int(4.4*u))
    t=6.6*u
    d.polygon([(cx-t*.55,cy-t),(cx-t*.55,cy+t),(cx+t*1.15,cy)],fill=(255,255,255,255))
    out.alpha_composite(orbit(True))
    # FAST lightning bolt on the orbit (yellow)
    a=math.radians(-rot); ang=math.radians(-40)
    ex,ey=rx*math.cos(ang),ry*math.sin(ang)
    bx=cx+ex*math.cos(a)-ey*math.sin(a); by=cy+ex*math.sin(a)+ey*math.cos(a)
    d=ImageDraw.Draw(out); bs=8*u
    d.ellipse([bx-bs*.95,by-bs*.95,bx+bs*.95,by+bs*.95],fill=(253,224,71,255))
    bolt=[(0.25,-1),(-0.55,0.1),(-0.05,0.1),(-0.3,1),(0.55,-0.15),(0.05,-0.15)]
    d.polygon([(bx+x*bs*.8,by+y*bs*.8) for x,y in bolt],fill=(124,58,237,255))
    # name: ONELLY EDIT
    f=ImageFont.truetype(FONT,int(8.6*u))
    txt="ONELLY EDIT"
    tw=d.textlength(txt,font=f)
    # letter spacing
    sp=0.5*u; total=sum(d.textlength(ch,font=f) for ch in txt)+sp*(len(txt)-1)
    x=54*u-total/2; y=59*u
    for ch in txt:
        d.text((x,y),ch,font=f,fill=(255,255,255,255),stroke_width=int(.9*u),stroke_fill=(15,12,45,255)); x+=d.textlength(ch,font=f)+sp
    # timeline clips
    y0,h=75.5*u,6.4*u
    segs=[(31,13,(255,255,255,235)),(45.5,9,(94,234,212,255)),(56,14,(255,255,255,235)),(71.5,5.5,(253,224,71,255))]
    for x,wd,colr in segs:
        d.rounded_rectangle([x*u,y0,(x+wd-1.3)*u,y0+h],radius=h/2,fill=colr)
    hx=44.7*u
    d.rounded_rectangle([hx-1*u,y0-3.5*u,hx+1*u,y0+h+3.5*u],radius=.8*u,fill=(253,224,71,255))
    d.ellipse([hx-2.6*u,y0-6.4*u,hx+2.6*u,y0-1.2*u],fill=(253,224,71,255))
    return out.resize((S,S),Image.LANCZOS)

def legacy(S,round_=False):
    base=render_bg(S*2); f=render_fg(S*2); sc=1.22
    f=f.resize((int(S*2*sc),)*2,Image.LANCZOS); o=(S*2-f.size[0])//2
    base.alpha_composite(f,(o,o))
    m=Image.new("L",(S*2,S*2),0); d=ImageDraw.Draw(m)
    if round_: d.ellipse([0,0,S*2-1,S*2-1],fill=255)
    else: d.rounded_rectangle([0,0,S*2-1,S*2-1],radius=int(S*2*.22),fill=255)
    base.putalpha(m); return base.resize((S,S),Image.LANCZOS)

for name,l,f in [("mdpi",48,108),("hdpi",72,162),("xhdpi",96,216),("xxhdpi",144,324),("xxxhdpi",192,432)]:
    p=f"resources/android/mipmap-{name}"; os.makedirs(p,exist_ok=True)
    legacy(l).save(f"{p}/ic_launcher.png"); legacy(l,True).save(f"{p}/ic_launcher_round.png")
    render_fg(f).save(f"{p}/ic_launcher_foreground.png"); render_bg(f).convert("RGB").save(f"{p}/ic_launcher_background.png")
ic=legacy(1024); ic.save("resources/icon-1024.png")

# brand banner with English name + tagline
W,H=1200,630
ban=lin_grad(W,[(20,16,48),(11,11,28),(30,12,56)]).crop((0,0,W,H))
ban.alpha_composite(ic.resize((400,400),Image.LANCZOS),(90,115))
d=ImageDraw.Draw(ban)
F="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
d.text((540,215),"Onelly Edit",font=ImageFont.truetype(F,96),fill=(255,255,255,255))
d.text((545,335),"Fast Video Editor",font=ImageFont.truetype(F,48),fill=(34,211,238,255))
d.text((545,410),"Cut. Edit. Export. Worldwide.",font=ImageFont.truetype(F,34),fill=(255,200,40,255))
ban.convert("RGB").save("resources/brand-banner.png")
