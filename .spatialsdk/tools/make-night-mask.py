"""生成 night_mask.png：横向 alpha 渐变的昼夜暗罩贴图（2048x1024, RGBA）。

RGB 恒为黑；alpha 只随 u（横向）变化，纵向恒定：
  - 以 u=0.75 为「夜半球中心」，角距 d<=63deg 处 alpha=NIGHT_ALPHA
  - 63deg<d<=117deg 线性过渡（54deg = 360deg 的 15%）
  - d>117deg 处 alpha=0（白天侧完全透明）
夜半球中心取 u=0.75 只是一个基准；createSphere 的 UV 原点未文档化，
真实朝向由运行时的 NIGHT_CENTER_U_DEG 常量校准。
"""
from PIL import Image

W, H = 2048, 1024
NIGHT_ALPHA = 0.72
CORE_DEG = 63.0      # 这以内是全暗
EDGE_DEG = 117.0     # 这以外全透明（过渡带 54deg = 360 * 15%）
NIGHT_CENTER_U = 0.75

img = Image.new("RGBA", (W, H))
px = img.load()

alphas = []
for x in range(W):
    u = (x + 0.5) / W
    d = abs(u - NIGHT_CENTER_U) * 360.0
    if d > 180.0:
        d = 360.0 - d
    if d <= CORE_DEG:
        a = NIGHT_ALPHA
    elif d >= EDGE_DEG:
        a = 0.0
    else:
        a = NIGHT_ALPHA * (EDGE_DEG - d) / (EDGE_DEG - CORE_DEG)
    alphas.append(int(round(a * 255)))

for y in range(H):
    for x in range(W):
        px[x, y] = (0, 0, 0, alphas[x])

img.save("app/src/main/assets/textures/night_mask.png", optimize=True)
print("size", img.size, "mode", img.mode)
print("alpha at u=0.00/0.25/0.50/0.75/1.00:",
      [alphas[int(u * (W - 1))] for u in (0.0, 0.25, 0.5, 0.75, 1.0)])
