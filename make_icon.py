#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""照悬浮窗样式生成 NetPulse 图标（多尺寸 mipmap）。"""
import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "res")
FONT_B = "C:/Windows/Fonts/consolab.ttf"
FONT_R = "C:/Windows/Fonts/consola.ttf"

# 与悬浮窗一致的配色
BG      = (0x10, 0x14, 0x18, 0xFF)
BORDER  = (0x48, 0xCA, 0xE4, 0xFF)
GREEN   = (0x4A, 0xDE, 0x80, 0xFF)
CYAN    = (0x48, 0xCA, 0xE4, 0xFF)
WHITE   = (0xFF, 0xFF, 0xFF, 0xFF)
DIMMER  = (0xB0, 0xB0, 0xB0, 0xFF)

SIZES = [48, 72, 96, 144, 192]


def rounded(size, radius_ratio, fill, outline=None, width=0):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = max(1, int(size * 0.055))
    r = int(size * radius_ratio)
    d.rounded_rectangle([pad, pad, size - pad - 1, size - pad - 1],
                        radius=r, fill=fill, outline=outline, width=width)
    return img


def draw_icon(size):
    S = size * 4  # 超采样，缩小时更干净
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    pad = int(S * 0.055)
    r = int(S * 0.20)
    bw = max(1, int(S * 0.018))
    d.rounded_rectangle([pad, pad, S - pad - 1, S - pad - 1],
                        radius=r, fill=BG, outline=BORDER, width=bw)

    # 状态灯（左上）
    dot_r = int(S * 0.048)
    cx, cy = int(S * 0.165), int(S * 0.255)
    d.ellipse([cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r], fill=GREEN)

    # 大数值 "29"
    fs = int(S * 0.335)
    try:
        fb = ImageFont.truetype(FONT_B, fs)
        fr = ImageFont.truetype(FONT_R, int(S * 0.155))
        fu = ImageFont.truetype(FONT_R, int(S * 0.125))
    except Exception:
        fb = fr = fu = ImageFont.load_default()

    d.text((int(S * 0.285), int(S * 0.375)), "29", font=fb, fill=WHITE, anchor="lm")

    # "ms"
    d.text((int(S * 0.635), int(S * 0.412)), "ms", font=fu, fill=DIMMER, anchor="lm")

    # 抖动 "±2.1"
    d.text((int(S * 0.285), int(S * 0.585)), "±2.1", font=fr, fill=DIMMER, anchor="lm")

    # 底部趋势柱（5 根，高低错落，青色）
    base_y = int(S * 0.875)
    bar_w = int(S * 0.078)
    gap = int(S * 0.042)
    heights = [0.42, 0.68, 0.52, 1.0, 0.76]
    total = len(heights) * bar_w + (len(heights) - 1) * gap
    x0 = int((S - total) / 2)
    max_h = int(S * 0.155)
    for i, hf in enumerate(heights):
        bh = max(int(S * 0.036), int(max_h * hf))
        lx = x0 + i * (bar_w + gap)
        d.rounded_rectangle([lx, base_y - bh, lx + bar_w, base_y],
                            radius=max(1, int(bar_w * 0.32)), fill=CYAN)

    return img.resize((size, size), Image.LANCZOS)


def main():
    for d in ["mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"]:
        os.makedirs(os.path.join(OUT, d), exist_ok=True)

    mapping = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in mapping.items():
        icon = draw_icon(size)
        icon.save(os.path.join(OUT, folder, "ic_launcher.png"))
        print("wrote", folder, size)

    # 预览图（放大看效果）
    preview = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon_preview.png")
    draw_icon(512).save(preview)
    print("preview ->", preview)


if __name__ == "__main__":
    main()
