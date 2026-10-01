"""Converts icon.ico (repo root) into Android launcher icons. Falls back to a default icon."""
import os
from PIL import Image, ImageDraw

SRC = "icon.ico"


def load():
    if os.path.exists(SRC):
        try:
            im = Image.open(SRC)
            try:
                im = im.ico.getimage(max(im.ico.sizes()))
            except Exception:
                im.load()
            print("Using", SRC)
            return im.convert("RGBA")
        except Exception as e:
            print("Could not read", SRC, "->", e)
    else:
        print(SRC, "not found, using default icon")
    im = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, 511, 511), radius=110, fill=(255, 61, 74, 255))
    d.polygon([(190, 140), (190, 372), (390, 256)], fill=(255, 255, 255, 255))
    return im


im = load()
side = max(im.size)
canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
canvas.paste(im, ((side - im.width) // 2, (side - im.height) // 2))

for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    out = f"app/src/main/res/mipmap-{name}"
    os.makedirs(out, exist_ok=True)
    canvas.resize((px, px), Image.LANCZOS).save(f"{out}/ic_launcher.png")
print("Icons generated")
