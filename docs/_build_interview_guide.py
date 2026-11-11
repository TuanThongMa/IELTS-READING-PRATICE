from pathlib import Path
import re

from docx import Document
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor

root = Path(r"E:\File From CC\Niên luận ngành")
source = root / "docs" / "_interview_guide_source.md"
output = root / "docs" / "IELTS_Reading_Luong_he_thong_va_on_phong_van_Backend.docx"
doc = Document()
section = doc.sections[0]
section.page_width = Inches(8.5)
section.page_height = Inches(11)
section.top_margin = Inches(.76)
section.bottom_margin = Inches(.72)
section.left_margin = Inches(.82)
section.right_margin = Inches(.82)
section.footer_distance = Inches(.36)

normal = doc.styles['Normal']
normal.font.name = 'Arial'
normal.font.size = Pt(10.5)
normal.font.color.rgb = RGBColor(25, 35, 49)
normal.paragraph_format.space_after = Pt(6)
normal.paragraph_format.line_spacing = 1.15
for name, size, before, after in [('Title', 21, 0, 11), ('Heading 1', 15, 16, 7), ('Heading 2', 11.7, 11, 4)]:
    style = doc.styles[name]
    style.font.name = 'Arial'
    style.font.size = Pt(size)
    style.font.bold = True
    style.font.color.rgb = RGBColor(0, 0, 0)
    style.paragraph_format.space_before = Pt(before)
    style.paragraph_format.space_after = Pt(after)
    style.paragraph_format.keep_with_next = True
for name in ('List Bullet', 'List Number'):
    style = doc.styles[name]
    style.font.name = 'Arial'
    style.font.size = Pt(10.5)
    style.paragraph_format.space_after = Pt(3)
title_style = doc.styles['Title']
for node in title_style._element.xpath('.//w:pBdr'):
    node.getparent().remove(node)

def decorate(cell, head=False, alternate=False):
    cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
    tcpr = cell._tc.get_or_add_tcPr()
    borders = OxmlElement('w:tcBorders')
    for edge in ('top', 'left', 'bottom', 'right'):
        tag = OxmlElement('w:' + edge)
        tag.set(qn('w:val'), 'single')
        tag.set(qn('w:sz'), '4')
        tag.set(qn('w:color'), 'D9D9D9')
        borders.append(tag)
    tcpr.append(borders)
    margins = OxmlElement('w:tcMar')
    for edge, value in (('top', 90), ('bottom', 90), ('left', 105), ('right', 105)):
        tag = OxmlElement('w:' + edge)
        tag.set(qn('w:w'), str(value))
        tag.set(qn('w:type'), 'dxa')
        margins.append(tag)
    tcpr.append(margins)
    if head or alternate:
        shd = OxmlElement('w:shd')
        shd.set(qn('w:fill'), 'DCE8F5' if head else 'F7F9FC')
        tcpr.append(shd)
    for para in cell.paragraphs:
        para.paragraph_format.space_after = Pt(0)
        para.paragraph_format.line_spacing = 1.08
        for run in para.runs:
            run.font.name = 'Arial'
            run.font.size = Pt(9)
            if head: run.bold = True

def add_table(lines):
    matrix = [[v.strip() for v in line.strip().strip('|').split('|')] for line in lines]
    if len(matrix) > 1 and all(set(v) <= {'-', ':', ' '} for v in matrix[1]):
        matrix.pop(1)
    cols = len(matrix[0])
    table = doc.add_table(rows=0, cols=cols)
    table.autofit = False
    widths = {2: (1.55, 5.09), 3: (1.32, 2.83, 2.49), 4: (1.24, 1.48, 1.24, 2.68)}.get(cols)
    if widths:
        for column, width in zip(table.columns, widths):
            column.width = Inches(width)
    for index, values in enumerate(matrix):
        cells = table.add_row().cells
        properties = table.rows[-1]._tr.get_or_add_trPr()
        properties.append(OxmlElement('w:cantSplit'))
        for c in range(cols):
            cells[c].text = values[c].replace('`', '') if c < len(values) else ''
            decorate(cells[c], index == 0, index > 0 and index % 2 == 0)
        if index == 0:
            table.rows[0]._tr.get_or_add_trPr().append(OxmlElement('w:tblHeader'))
    doc.add_paragraph().paragraph_format.space_after = Pt(0)

def rich(paragraph, text):
    for piece in re.split(r'(\*\*.*?\*\*|`[^`]+`)', text):
        if not piece: continue
        if piece.startswith('**') and piece.endswith('**'):
            run = paragraph.add_run(piece[2:-2])
            run.bold = True
        elif piece.startswith('`') and piece.endswith('`'):
            run = paragraph.add_run(piece[1:-1])
            run.font.name = 'Consolas'
            run.font.size = Pt(9.3)
        else:
            paragraph.add_run(piece)

lines = source.read_text(encoding='utf-8').splitlines()
table_lines = []
code_mode = False
for line_number, line in enumerate(lines):
    if line.startswith('|'):
        table_lines.append(line)
        continue
    if table_lines:
        add_table(table_lines)
        table_lines = []
    if line.startswith('```'):
        code_mode = not code_mode
        continue
    if code_mode:
        para = doc.add_paragraph()
        para.paragraph_format.left_indent = Inches(.16)
        para.paragraph_format.space_after = Pt(1)
        para.paragraph_format.keep_with_next = line_number + 1 < len(lines) and not lines[line_number + 1].startswith('```')
        run = para.add_run(line)
        run.font.name = 'Consolas'
        run.font.size = Pt(8.6)
        continue
    if not line.strip(): continue
    if line.startswith('# '):
        para = doc.add_paragraph(style='Title')
        rich(para, line[2:])
        borders = OxmlElement('w:pBdr')
        bottom = OxmlElement('w:bottom')
        bottom.set(qn('w:val'), 'nil')
        borders.append(bottom)
        para._p.get_or_add_pPr().append(borders)
    elif line.startswith('## '): doc.add_heading(line[3:], 1)
    elif line.startswith('### '): doc.add_heading(line[4:], 2)
    elif line.startswith('- '): rich(doc.add_paragraph(style='List Bullet'), line[2:])
    elif re.match(r'^\d+\. ', line):
        para = doc.add_paragraph()
        para.paragraph_format.left_indent = Inches(.29)
        para.paragraph_format.first_line_indent = Inches(-.23)
        value, content = line.split('. ', 1)
        para.add_run(value + '.  ')
        rich(para, content)
    else: rich(doc.add_paragraph(), line)
if table_lines: add_table(table_lines)

footer = section.footer.paragraphs[0]
footer.alignment = WD_ALIGN_PARAGRAPH.RIGHT
run = footer.add_run('IELTS Reading Practice  |  ')
run.font.name = 'Arial'; run.font.size = Pt(8); run.font.color.rgb = RGBColor(89, 99, 112)
field = OxmlElement('w:fldSimple')
field.set(qn('w:instr'), 'PAGE')
footer._p.append(field)
doc.core_properties.title = 'Luồng hệ thống IELTS Reading và kiến thức Backend cho phỏng vấn'
doc.core_properties.subject = 'Đối chiếu luồng nghiệp vụ, Java, Spring Boot và PostgreSQL với mã nguồn'
doc.core_properties.author = 'IELTS Reading Practice'
doc.save(output)
print(output)
