import sys, os
sys.argv=["x"]
import gen_art as g, json, base64, urllib.request
P = ("A single gearwheel (cog) with eight teeth and a round centre hole, drawn as one bold expressive enso-like Japanese calligraphy brush stroke (shodo), "
     "dry-brush texture, ink splatter-free, centred, filling about 80% of a square frame, plenty of clean margin. "
     "No text, no letters, no characters, no watermark, no seal stamp, no border. ")
T = {"light": "Black sumi ink on a plain flat off-white washi paper background.",
     "dark": "Pale white ink on a plain flat near-black paper background, as if white ink on black paper."}
for th, t in T.items():
    path = os.path.join(g.ROOT, f"settings-{th}.png")
    if os.path.exists(path): continue
    body = json.dumps({"prompt": P + t, "size": "1024x1024", "n": 1, "quality": "medium", "output_format": "png"}).encode()
    for a in range(6):
        try:
            r = json.load(urllib.request.urlopen(urllib.request.Request(g.url, body, {**g.AUTH, "Content-Type": "application/json"}), timeout=300))
            open(path, "wb").write(base64.b64decode(r["data"][0]["b64_json"])); open(path + ".prompt.txt", "w").write(P + t); print("ok", path); break
        except Exception as e:
            print("err", str(e)[:100]); import time; time.sleep(30)
