# 2.2 图标

最终资源：`app/src/main/res/drawable-nodpi/ic_launcher_v22.png`。使用内置 ImageGen 工具生成并进行一次细化；PNG 已复制到仓库，Manifest 的 icon/roundIcon 均接入自适应图标。

最终细化提示词（输入是初次生成的月牙与声波图案）：

> Refine this Android launcher icon into a completely flat, production ready icon. Keep the crescent and three rounded audio bars and their central placement. Change the entire square canvas background to a perfectly uniform opaque solid #172C45 navy, edge to edge, including the area inside the crescent. Remove ALL transparency, feathering, blur, glow, shadows, highlights and mottled gradients. Use a single solid pale ivory fill for the crescent and a single solid muted teal fill for the bars. All contours must be crisp and clean like a simple geometric logo. No text. No border. No rounded square enclosing the design. One opaque square image. Preserve wide safe padding.

中央图案保留宽留白，由 Android 施加设备默认圆形或其他图标蒙版。原有麦克风矢量资源未被覆写。
