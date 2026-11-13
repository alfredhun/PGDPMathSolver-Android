# PGDPMathSolver-Android

基于深度学习与符号逻辑推理的 Android 平面几何解题与辅助学习应用。

## 特性亮点

- **📸 题目拍摄与裁剪**：集成 CameraX，支持题目区域精准裁剪与 EXIF 角度校正。
- **📝 端侧中英文 OCR**：基于 Google ML Kit 离线文字识别引擎，本地快速提取题干文字。
- **📐 本地符号几何求解器**：基于 Chaquopy + Python (SymPy, Kanren, PyParsing) 实现 InterGPS 几何形式化定理推演。
- **🤖 混合双模求解**：
  - **本地几何引擎 + 大模型**：端侧提取题干与几何要素，纯文本送入 DeepSeek / GLM 推理，节省带宽与 Token。
  - **端云多模态协同**：支持视觉多模态大模型直接解析复杂图文。
- **✨ 护眼渲染与 KaTeX 公式**：Jetpack Compose 现代化界面，内置离线 KaTeX 与 Markdown 公式步骤排版。

## 持续集成 (GitHub Actions)

项目已配置 GitHub Actions 自动构建工作流（`.github/workflows/build-apk.yml`）：
- 提交代码至 `main` 分支或手动触发（`workflow_dispatch`）即可自动编译打包。
- 编译完成后可在 Actions Artifacts 中下载调试版 APK（`pgdp-math-solver-debug-apk`）。
