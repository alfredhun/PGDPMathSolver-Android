# -*- coding: utf-8 -*-
"""
local_solver.py - 放置于 Android 工程 app/src/main/python/local_solver.py
供 Chaquopy 在端侧离线调用：
1. 接收 Kotlin 端侧 ML Kit 离线识别的 OCR 文本与图片
2. OpenCV 提取几何线段与视觉元数据
3. 符号化提取几何图元（三角形、直角三角形、等腰、平行四边形、垂直、平行、中点、角平分线）
4. 端侧几何定理推导引擎（三角形内角和、等腰三线合一、勾股定理、平行线判定与截割、平行四边形性质）
5. SymPy 符号代数联立求解，并输出规范的 LaTeX 中文分步推导演算步骤
"""
import json
import os
import re
import sys
from typing import Any, Dict, List, Optional, Set, Tuple

import cv2
import numpy as np
import sympy as sp


def _normalize_math_text(text: str) -> str:
    # 保护复合词不被单字替换误伤
    text = text.replace("平行四边形", "__PARALLELOGRAM__")
    text = text.replace("直角三角形", "__RTTRIANGLE__")
    text = text.replace("等腰三角形", "__ISOTRIANGLE__")
    text = text.replace("等边三角形", "__EQUTRIANGLE__")

    replacements = {
        "∥": "//", "＝": "=", "＋": "+", "－": "-", "×": "*", "÷": "/",
        "（": "(", "）": ")", "，": ",", "。": ".", "²": "**2", "³": "**3",
        "：": ":", "；": ";", "、": ",", "度": "°", "三角形": "△",
        "平行于": "//", "平行": "//", "垂直于": "⊥", "垂直": "⊥"
    }
    for k, v in replacements.items():
        text = text.replace(k, v)

    text = text.replace("__PARALLELOGRAM__", "平行四边形")
    text = text.replace("__RTTRIANGLE__", "Rt△")
    text = text.replace("__ISOTRIANGLE__", "等腰△")
    text = text.replace("__EQUTRIANGLE__", "等边△")

    text = re.sub(r"√\(([^)]+)\)", r"sqrt(\1)", text)
    text = re.sub(r"√([A-Za-z0-9]+)", r"sqrt(\1)", text)
    return text


def _split_ocr_text(ocr_text: str) -> Tuple[str, List[str]]:
    if not ocr_text or not ocr_text.strip():
        return "", []

    text_lines: List[str] = []
    vertex_points: List[str] = []

    for line in ocr_text.splitlines():
        clean = line.strip()
        if not clean:
            continue
        # 识别图形顶点标签（如 "A", "B", "C", "A B C"）
        letters = re.findall(r"\b([A-Z]['′]?\d?)\b", clean)
        if len(letters) > 0 and len(clean.replace(" ", "")) <= 4 and not re.search(r"[=\d°\u4e00-\u9fa5]", clean):
            for l in letters:
                norm = l.replace("′", "'")
                if norm not in vertex_points:
                    vertex_points.append(norm)
        else:
            text_lines.append(clean)

    question_text = _normalize_math_text("\n".join(text_lines))
    return question_text, vertex_points


def _extract_basic_diagram_primitives(img_bgr: np.ndarray) -> Dict[str, Any]:
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 50, 150, apertureSize=3)
    lines_p = cv2.HoughLinesP(
        edges,
        rho=1,
        theta=np.pi / 180,
        threshold=45,
        minLineLength=25,
        maxLineGap=8,
    )

    detected_lines = []
    if lines_p is not None:
        for item in lines_p[:40]:
            x1, y1, x2, y2 = [int(v) for v in item[0]]
            detected_lines.append({"p1": [x1, y1], "p2": [x2, y2]})

    return {
        "image_size": [int(img_bgr.shape[1]), int(img_bgr.shape[0])],
        "detected_segment_count": len(detected_lines),
        "raw_segments": detected_lines,
    }


def _assemble_logic_forms(question_text: str, points: List[str]) -> List[str]:
    """提取形式化 PGDP 谓词表达式，用于本地推理和省流模式传递给大模型。"""
    forms: List[str] = []

    # 1. 线段长度与数值
    for m in re.finditer(r"\b([A-Z][']?)([A-Z][']?)\s*=\s*([0-9]+(?:\.[0-9]+)?|sqrt\([^)]+\))", question_text):
        p1, p2, val = m.group(1), m.group(2), m.group(3)
        forms.append(f"Equals(LengthOf(Line({p1}, {p2})), {val})")

    # 2. 线段等长
    for m in re.finditer(r"\b([A-Z][']?)([A-Z][']?)\s*=\s*([A-Z][']?)([A-Z][']?)\b", question_text):
        a1, a2, b1, b2 = m.groups()
        forms.append(f"Equals(LengthOf(Line({a1}, {a2})), LengthOf(Line({b1}, {b2})))")

    # 3. 角度大小
    for m in re.finditer(r"∠([A-Z]{1,3})\s*=\s*([0-9]+(?:\.[0-9]+)?)°?", question_text):
        ang, val = m.group(1), m.group(2)
        if len(ang) == 3:
            forms.append(f"Equals(MeasureOf(Angle({ang[0]}, {ang[1]}, {ang[2]})), {val})")
        else:
            forms.append(f"Equals(MeasureOf(Angle({ang})), {val})")

    # 4. 垂直关系
    for m in re.finditer(r"\b([A-Z]{2})\s*⊥\s*([A-Z]{2})\b", question_text):
        l1, l2 = m.group(1), m.group(2)
        forms.append(f"Perpendicular(Line({l1[0]}, {l1[1]}), Line({l2[0]}, {l2[1]}))")

    # 5. 平行关系
    for m in re.finditer(r"\b([A-Z]{2})\s*//\s*([A-Z]{2})\b", question_text):
        l1, l2 = m.group(1), m.group(2)
        forms.append(f"Parallel(Line({l1[0]}, {l1[1]}), Line({l2[0]}, {l2[1]}))")

    # 6. 三角形
    for m in re.finditer(r"(Rt|等腰|等边)?△\s*([A-Z]{3})", question_text):
        kind, tri = m.group(1) or "", m.group(2)
        forms.append(f"Polygon({tri[0]}, {tri[1]}, {tri[2]})")
        if "Rt" in kind:
            forms.append(f"RightTriangle({tri[0]}, {tri[1]}, {tri[2]})")
        elif "等腰" in kind:
            forms.append(f"IsoscelesTriangle({tri[0]}, {tri[1]}, {tri[2]})")
        elif "等边" in kind:
            forms.append(f"EquilateralTriangle({tri[0]}, {tri[1]}, {tri[2]})")
        for ch in tri:
            if ch not in points:
                points.append(ch)

    # 7. 四边形
    for m in re.finditer(r"(平行四边形|矩形|菱形|正方形|梯形|四边形)\s*([A-Z]{4})", question_text):
        qkind, quad = m.group(1), m.group(2)
        forms.append(f"{qkind}({quad[0]}, {quad[1]}, {quad[2]}, {quad[3]})")
        for ch in quad:
            if ch not in points:
                points.append(ch)

    # 8. 中点
    for m in re.finditer(r"([A-Z])\s*是\s*([A-Z]{2})\s*(?:的)?中点", question_text):
        mid, seg = m.group(1), m.group(2)
        forms.append(f"IsMidpointOf({mid}, Line({seg[0]}, {seg[1]}))")

    # 9. 角平分线
    for m in re.finditer(r"([A-Z]{2})\s*平分\s*∠([A-Z]{3})", question_text):
        line, ang = m.group(1), m.group(2)
        forms.append(f"BisectsAngle(Line({line[0]}, {line[1]}), Angle({ang[0]}, {ang[1]}, {ang[2]}))")

    return list(dict.fromkeys(forms))


class GeometryTheoremEngine:
    """端侧符号几何定理推导与方程求解引擎。"""

    def __init__(self):
        self.equations: List[sp.Eq] = []
        self.steps: List[str] = []
        self.symbols: Dict[str, sp.Symbol] = {}
        self.triangles: List[Tuple[str, str, str]] = []
        self.right_triangles: Dict[Tuple[str, str, str], str] = {}
        self.isosceles_triangles: List[Tuple[str, str, str, str]] = []
        self.quadrilaterals: List[Tuple[str, str, str, str, str]] = []
        self.perpendiculars: List[Tuple[str, str, str, str]] = []
        self.parallels: List[Tuple[str, str, str, str]] = []
        self.midpoints: List[Tuple[str, str, str]] = []
        self.angle_bisectors: List[Tuple[str, str, str, str]] = []
        self.text: str = ""

    def sym(self, name: str) -> sp.Symbol:
        clean = re.sub(r"[^A-Za-z0-9_]", "_", name)
        if clean not in self.symbols:
            self.symbols[clean] = sp.Symbol(clean, positive=True)
        return self.symbols[clean]

    def line_sym(self, p1: str, p2: str) -> sp.Symbol:
        a, b = min(p1, p2), max(p1, p2)
        return self.sym(f"len_{a}{b}")

    def angle_sym(self, *pts: str) -> sp.Symbol:
        if len(pts) == 1:
            return self.sym(f"ang_{pts[0]}")
        elif len(pts) == 3:
            v1, v2, v3 = pts
            e1, e3 = min(v1, v3), max(v1, v3)
            return self.sym(f"ang_{e1}_{v2}_{e3}")
        return self.sym(f"ang_{''.join(pts)}")

    def add_eq(self, eq: sp.Eq, step_desc: str):
        self.equations.append(eq)
        self.steps.append(step_desc)

    def parse_problem(self, text: str):
        self.text = text

        # 1. 识别三角形与直角三角形
        for m in re.finditer(r"(Rt)?△\s*([A-Z])([A-Z])([A-Z])", text):
            is_rt = bool(m.group(1))
            a, b, c = m.group(2), m.group(3), m.group(4)
            tri = (a, b, c)
            if tri not in self.triangles and (b, c, a) not in self.triangles and (c, a, b) not in self.triangles:
                self.triangles.append(tri)
                if is_rt:
                    self.right_triangles[tri] = c

        for m in re.finditer(r"等腰△\s*([A-Z])([A-Z])([A-Z])", text):
            a, b, c = m.group(1), m.group(2), m.group(3)
            self.isosceles_triangles.append((a, b, c, a))

        # 2. 识别四边形
        for m in re.finditer(r"(平行四边形|矩形|菱形|正方形|梯形|四边形)\s*([A-Z])([A-Z])([A-Z])([A-Z])", text):
            qtype, a, b, c, d = m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)
            self.quadrilaterals.append((a, b, c, d, qtype))

        # 3. 直角条件 ∠C=90°
        for m in re.finditer(r"∠([A-Z])\s*=\s*90", text):
            v = m.group(1)
            for tri in self.triangles:
                if v in tri:
                    self.right_triangles[tri] = v

        # 4. 垂直关系 AD⊥BC
        for m in re.finditer(r"([A-Z]{2})\s*⊥\s*([A-Z]{2})", text):
            l1, l2 = m.group(1), m.group(2)
            self.perpendiculars.append((l1[0], l1[1], l2[0], l2[1]))
            common = set(l1) & set(l2)
            if common:
                foot = next(iter(common))
                other1 = l1[0] if l1[1] == foot else l1[1]
                other2 = l2[0] if l2[1] == foot else l2[1]
                self.add_eq(
                    sp.Eq(self.angle_sym(other1, foot, other2), 90),
                    f"【垂直性质】由 ${l1} \\perp {l2}$，垂足为点 ${foot}$，得 $\\angle {other1}{foot}{other2} = 90^\\circ$。"
                )
                self.right_triangles[(other1, foot, other2)] = foot

        # 5. 平行线 DE // BC
        for m in re.finditer(r"([A-Z]{2})\s*//\s*([A-Z]{2})", text):
            l1, l2 = m.group(1), m.group(2)
            self.parallels.append((l1[0], l1[1], l2[0], l2[1]))

        # 6. 中点条件
        for m in re.finditer(r"([A-Z])\s*是\s*([A-Z]{2})\s*(?:的)?中点", text):
            mid, seg = m.group(1), m.group(2)
            p1, p2 = seg[0], seg[1]
            self.midpoints.append((mid, p1, p2))

        # 7. 角平分线
        for m in re.finditer(r"([A-Z]{2})\s*平分\s*∠([A-Z]{3})", text):
            line, ang = m.group(1), m.group(2)
            v = ang[1]
            e1, e2 = ang[0], ang[2]
            d = line[1] if line[0] == v else line[0]
            self.angle_bisectors.append((v, d, e1, e2))

        # 8. 边长数值与已知线段
        for m in re.finditer(r"\b([A-Z])([A-Z])\s*=\s*([0-9]+(?:\.[0-9]+)?|sqrt\([^)]+\))", text):
            p1, p2, val = m.group(1), m.group(2), m.group(3)
            val_sp = sp.sympify(val)
            self.add_eq(
                sp.Eq(self.line_sym(p1, p2), val_sp),
                f"【已知条件】已知线段 ${p1}{p2} = {sp.latex(val_sp)}$。"
            )

        if self.triangles:
            ta, tb, tc = self.triangles[0]
            abc_map = {'a': (tb, tc), 'b': (ta, tc), 'c': (ta, tb)}
            for m in re.finditer(r"\b([abc])\s*=\s*([0-9]+(?:\.[0-9]+)?|sqrt\([^)]+\))", text):
                ch, val = m.group(1), m.group(2)
                p1, p2 = abc_map[ch]
                val_sp = sp.sympify(val)
                self.add_eq(
                    sp.Eq(self.line_sym(p1, p2), val_sp),
                    f"【已知条件】已知边长 ${ch} = {p1}{p2} = {sp.latex(val_sp)}$。"
                )
                self.add_eq(sp.Eq(self.sym(ch), val_sp), f"【已知条件】代数记号 ${ch} = {sp.latex(val_sp)}$。")

        # 边长连等如 AB=AC=10 或 AB=AC
        for m in re.finditer(r"\b([A-Z]{2})\s*=\s*([A-Z]{2})(?:\s*=\s*([0-9]+(?:\.[0-9]+)?))?", text):
            s1, s2, val = m.group(1), m.group(2), m.group(3)
            p1a, p1b = s1[0], s1[1]
            p2a, p2b = s2[0], s2[1]
            common = set(s1) & set(s2)
            apex = next(iter(common)) if common else p1a
            base1 = p1b if p1a == apex else p1a
            base2 = p2b if p2a == apex else p2a
            self.isosceles_triangles.append((apex, base1, base2, apex))
            self.add_eq(
                sp.Eq(self.line_sym(p1a, p1b), self.line_sym(p2a, p2b)),
                f"【等腰结构】由 ${s1} = {s2}$，可知以点 ${apex}$ 为顶点的等腰三角形。"
            )
            if val:
                v_sp = sp.sympify(val)
                self.add_eq(sp.Eq(self.line_sym(p1a, p1b), v_sp), f"【已知条件】${s1} = {v_sp}$。")
                self.add_eq(sp.Eq(self.line_sym(p2a, p2b), v_sp), f"【已知条件】${s2} = {v_sp}$。")

        # 9. 角度大小
        for m in re.finditer(r"∠([A-Z]{1,3})\s*=\s*([0-9]+(?:\.[0-9]+)?)°?", text):
            ang, val = m.group(1), m.group(2)
            val_sp = sp.sympify(val)
            if len(ang) == 1:
                self.add_eq(
                    sp.Eq(self.angle_sym(ang), val_sp),
                    f"【已知条件】已知 $\\angle {ang} = {val_sp}^\\circ$。"
                )
                for tri in self.triangles:
                    if ang in tri:
                        others = [p for p in tri if p != ang]
                        self.add_eq(
                            sp.Eq(self.angle_sym(others[0], ang, others[1]), self.angle_sym(ang)),
                            f"在 $\\triangle {tri[0]}{tri[1]}{tri[2]}$ 中，记 $\\angle {others[0]}{ang}{others[1]} = \\angle {ang}$。"
                        )
            elif len(ang) == 3:
                self.add_eq(
                    sp.Eq(self.angle_sym(ang[0], ang[1], ang[2]), val_sp),
                    f"【已知条件】已知 $\\angle {ang} = {val_sp}^\\circ$。"
                )
                self.add_eq(
                    sp.Eq(self.angle_sym(ang[1]), self.angle_sym(ang[0], ang[1], ang[2])),
                    f"记顶角 $\\angle {ang[1]} = \\angle {ang}$。"
                )

    def apply_theorems(self):
        # 定理 1: 三角形内角和定理 (180°)
        for tri in self.triangles:
            a, b, c = tri
            sa = self.angle_sym(b, a, c)
            sb = self.angle_sym(a, b, c)
            sc = self.angle_sym(a, c, b)
            self.add_eq(sp.Eq(self.angle_sym(a), sa), f"在 $\\triangle {a}{b}{c}$ 中，记 $\\angle {a} = \\angle {b}{a}{c}$。")
            self.add_eq(sp.Eq(self.angle_sym(b), sb), f"在 $\\triangle {a}{b}{c}$ 中，记 $\\angle {b} = \\angle {a}{b}{c}$。")
            self.add_eq(sp.Eq(self.angle_sym(c), sc), f"在 $\\triangle {a}{b}{c}$ 中，记 $\\angle {c} = \\angle {a}{c}{b}$。")
            self.add_eq(
                sp.Eq(sa + sb + sc, 180),
                f"【定理应用：三角形内角和定理】在 $\\triangle {a}{b}{c}$ 中，三个内角和等于 $180^\\circ$：$$\\angle {a} + \\angle {b} + \\angle {c} = 180^\\circ$$"
            )

        # 定理 2: 等腰三角形性质 (等边对等角、三线合一)
        for (apex, b1, b2, _) in self.isosceles_triangles:
            sb1 = self.angle_sym(b1)
            sb2 = self.angle_sym(b2)
            self.add_eq(
                sp.Eq(sb1, sb2),
                f"【定理应用：等腰三角形等边对等角】因 ${apex}{b1} = {apex}{b2}$，得两底角相等：$$\\angle {b1} = \\angle {b2}$$"
            )

            # 中线触发三线合一
            for (foot, p1, p2) in self.midpoints:
                if {p1, p2} == {b1, b2}:
                    self.add_eq(
                        sp.Eq(self.line_sym(b1, foot), self.line_sym(b1, b2) / 2),
                        f"【定理应用：等腰三角形三线合一】顶角顶点 ${apex}$ 到底边中点 ${foot}$ 的连线满足：$${b1}{foot} = {b2}{foot} = \\frac{{1}}{{2}}{b1}{b2}$$"
                    )
                    self.add_eq(
                        sp.Eq(self.line_sym(b2, foot), self.line_sym(b1, b2) / 2),
                        f"同理得：$${b2}{foot} = \\frac{{1}}{{2}}{b1}{b2}$$"
                    )
                    self.add_eq(
                        sp.Eq(self.angle_sym(apex, foot, b1), 90),
                        f"【定理应用：等腰三角形三线合一】底边中线 ${apex}{foot}$ 同时为底边高线，故：$$\\angle {apex}{foot}{b1} = 90^\\circ$$"
                    )
                    self.right_triangles[(apex, foot, b1)] = foot
                    self.right_triangles[(apex, foot, b2)] = foot

            # 高线触发三线合一
            for (l1a, l1b, l2a, l2b) in self.perpendiculars:
                if {l2a, l2b} == {b1, b2} and apex in (l1a, l1b):
                    foot = l1b if l1a == apex else l1a
                    self.add_eq(
                        sp.Eq(self.line_sym(b1, foot), self.line_sym(b1, b2) / 2),
                        f"【定理应用：等腰三角形三线合一】顶角高线 ${apex}{foot} \\perp {b1}{b2}$ 同时也是底边中线，故：$${b1}{foot} = {b2}{foot} = \\frac{{1}}{{2}}{b1}{b2}$$"
                    )
                    self.right_triangles[(apex, foot, b1)] = foot
                    self.right_triangles[(apex, foot, b2)] = foot

        # 定理 3: 勾股定理 (直角三角形 a^2 + b^2 = c^2)
        for tri, right_v in self.right_triangles.items():
            legs = [p for p in tri if p != right_v]
            if len(legs) == 2:
                l1, l2 = legs[0], legs[1]
                leg1 = self.line_sym(right_v, l1)
                leg2 = self.line_sym(right_v, l2)
                hyp = self.line_sym(l1, l2)
                self.add_eq(
                    sp.Eq(leg1**2 + leg2**2, hyp**2),
                    f"【定理应用：勾股定理】在 $\\text{{Rt}}\\triangle {tri[0]}{tri[1]}{tri[2]}$（$\\angle {right_v} = 90^\\circ$）中：$${right_v}{l1}^2 + {right_v}{l2}^2 = {l1}{l2}^2$$"
                )

        # 定理 4: 平行截割定理 / 相似三角形
        for (l1a, l1b, l2a, l2b) in self.parallels:
            for tri in self.triangles:
                a, b, c = tri
                if {l2a, l2b} == {b, c}:
                    d, e = l1a, l1b
                    self.add_eq(
                        sp.Eq(self.line_sym(a, d) / self.line_sym(a, b), self.line_sym(d, e) / self.line_sym(b, c)),
                        f"【定理应用：平行截割与相似】因 ${d}{e} \\parallel {b}{c}$，得 $\\triangle A{d}{e} \\sim \\triangle A{b}{c}$，故：$$\\frac{{{a}{d}}}{{{a}{b}}} = \\frac{{{d}{e}}}{{{b}{c}}}$$"
                    )
                    self.add_eq(
                        sp.Eq(self.line_sym(a, d) / self.line_sym(d, b), self.line_sym(a, e) / self.line_sym(e, c)),
                        f"【定理应用：平行线分线段成比例】因 ${d}{e} \\parallel {b}{c}$，得：$$\\frac{{{a}{d}}}{{{d}{b}}} = \\frac{{{a}{e}}}{{{e}{c}}}$$"
                    )
                    self.add_eq(
                        sp.Eq(self.line_sym(a, d) + self.line_sym(d, b), self.line_sym(a, b)),
                        f"【线段关系】点 ${d}$ 在线段 ${a}{b}$ 上，故：$${a}{d} + {d}{b} = {a}{b}$$"
                    )
                    self.add_eq(
                        sp.Eq(self.line_sym(a, e) + self.line_sym(e, c), self.line_sym(a, c)),
                        f"【线段关系】点 ${e}$ 在线段 ${a}{c}$ 上，故：$${a}{e} + {e}{c} = {a}{c}$$"
                    )

        # 定理 5: 平行四边形性质
        for (a, b, c, d, qtype) in self.quadrilaterals:
            if any(k in qtype for k in ["平行四边形", "矩形", "菱形", "正方形"]):
                self.add_eq(sp.Eq(self.line_sym(a, b), self.line_sym(c, d)), f"【平行四边形性质】对边相等：$${a}{b} = {c}{d}$$")
                self.add_eq(sp.Eq(self.line_sym(a, d), self.line_sym(b, c)), f"【平行四边形性质】对边相等：$${a}{d} = {b}{c}$$")
                self.add_eq(sp.Eq(self.angle_sym(a), self.angle_sym(c)), f"【平行四边形性质】对角相等：$$\\angle {a} = \\angle {c}$$")
                self.add_eq(sp.Eq(self.angle_sym(b), self.angle_sym(d)), f"【平行四边形性质】对角相等：$$\\angle {b} = \\angle {d}$$")
                self.add_eq(
                    sp.Eq(self.angle_sym(a) + self.angle_sym(b), 180),
                    f"【平行四边形性质】同旁内角互补：$$\\angle {a} + \\angle {b} = 180^\\circ$$"
                )

    def extract_target_symbol(self) -> Tuple[Optional[sp.Symbol], str]:
        m_ang = re.search(r"(?:求|计算|则)\s*∠([A-Z]{1,3})", self.text)
        if m_ang:
            ang = m_ang.group(1)
            target_sym = self.angle_sym(ang)
            return target_sym, f"\\angle {ang}"

        m_len = re.search(r"(?:求|计算|则)\s*([A-Z]{2})\s*(?:的长|等于|=|\?|$)", self.text)
        if m_len:
            seg = m_len.group(1)
            target_sym = self.line_sym(seg[0], seg[1])
            return target_sym, seg

        m_abc = re.search(r"(?:求|计算|则)\s*(?:斜边)?\s*([abc])\s*(?:的长|等于|=|\?|$)", self.text)
        if m_abc:
            ch = m_abc.group(1)
            if ch in self.symbols:
                return self.symbols[ch], ch
            elif self.triangles:
                ta, tb, tc = self.triangles[0]
                abc_map = {'a': (tb, tc), 'b': (ta, tc), 'c': (ta, tb)}
                p1, p2 = abc_map[ch]
                return self.line_sym(p1, p2), f"{ch} ({p1}{p2})"

        m_var = re.search(r"(?:求|计算|则)\s*([a-z])\s*(?:的值|=|\?|$)", self.text)
        if m_var:
            v = m_var.group(1)
            if v in self.symbols:
                return self.symbols[v], v

        return None, ""

    def solve(self) -> Dict[str, Any]:
        self.apply_theorems()
        target_sym, target_name = self.extract_target_symbol()

        unique_equations = list(dict.fromkeys(self.equations))
        try:
            sol = sp.solve(unique_equations, dict=True)
            if sol:
                first_sol = sol[0]
                target_val = None
                if target_sym and target_sym in first_sol:
                    target_val = first_sol[target_sym]
                else:
                    for s, val in first_sol.items():
                        if not val.is_symbol and val.is_number:
                            target_sym = s
                            target_val = val
                            target_name = s.name
                            break

                if target_val is not None:
                    is_angle = "ang" in (target_sym.name if target_sym else "") or "∠" in target_name
                    unit = "^\\circ" if is_angle else ""
                    val_latex = sp.latex(target_val)
                    final_ans = f"${target_name} = {val_latex}{unit}$"
                    self.steps.append(
                        f"【联立消元求解】联立上述几何定理方程组，解得目标值：$${target_name} = {val_latex}{unit}$$"
                    )

                    steps_md = "\n\n".join(
                        f"**步骤 {i + 1}**：{desc}" for i, desc in enumerate(self.steps)
                    )

                    return {
                        "solved": True,
                        "confidence": 0.95,
                        "final_answer": final_ans,
                        "steps_markdown": steps_md,
                    }
        except Exception as e:
            self.steps.append(f"符号推理提示: {e}")

        return {
            "solved": False,
            "confidence": 0.45,
            "final_answer": "本地定理库暂未直接闭合求解",
            "steps_markdown": "\n\n".join(self.steps) if self.steps else "未能识别出明确的几何推导要素，建议点击下方「用 AI 再解一遍」。",
        }


def parse_only(image_path: str, ocr_text: str = "") -> str:
    if not os.path.exists(image_path):
        raise FileNotFoundError(f"图片不存在: {image_path}")

    img_bgr = cv2.imread(image_path)
    if img_bgr is None:
        raise ValueError(f"无法解码图片: {image_path}")

    question_text, points = _split_ocr_text(ocr_text)
    logic_forms = _assemble_logic_forms(question_text, points)
    vision_info = _extract_basic_diagram_primitives(img_bgr)

    result = {
        "question_text": question_text,
        "points": points,
        "diagram_logic_forms": logic_forms,
        "vision_meta": vision_info,
    }
    return json.dumps(result, ensure_ascii=False)


def parse_and_solve_local(image_path: str, ocr_text: str = "") -> str:
    parsed = json.loads(parse_only(image_path, ocr_text))
    question_text = parsed.get("question_text", "")

    engine = GeometryTheoremEngine()
    engine.parse_problem(question_text)
    solve_out = engine.solve()

    payload = {
        "solved": solve_out["solved"],
        "confidence": solve_out["confidence"],
        "final_answer": solve_out["final_answer"],
        "steps_markdown": solve_out["steps_markdown"],
        "parsed_geometry": parsed,
    }
    return json.dumps(payload, ensure_ascii=False)
