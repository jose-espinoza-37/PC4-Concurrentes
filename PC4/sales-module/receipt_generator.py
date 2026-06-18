"""
receipt_generator.py
Dog Messenger — Modulo de Ventas
Genera comprobantes PDF con ReportLab y texto plano como fallback.
"""

from datetime import datetime
from io import BytesIO
import db

try:
    from reportlab.lib.pagesizes import A4
    from reportlab.lib import colors
    from reportlab.lib.units import mm
    from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib.enums import TA_CENTER, TA_RIGHT
    _HAS_REPORTLAB = True
except ImportError:
    _HAS_REPORTLAB = False


def generate_receipt_pdf(order_id):
    """Genera un comprobante PDF. Retorna (filename, pdf_bytes) o None si falla."""
    if not _HAS_REPORTLAB:
        return None

    order = db.get_order(order_id)
    if not order:
        return None

    buf = BytesIO()
    doc = SimpleDocTemplate(buf, pagesize=A4,
                            topMargin=20*mm, bottomMargin=20*mm,
                            leftMargin=20*mm, rightMargin=20*mm)
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle("Title2", parent=styles["Title"],
                                 fontSize=18, spaceAfter=4*mm)
    subtitle_style = ParagraphStyle("Sub", parent=styles["Normal"],
                                     fontSize=11, alignment=TA_CENTER,
                                     spaceAfter=6*mm)
    footer_style = ParagraphStyle("Footer", parent=styles["Normal"],
                                   fontSize=10, alignment=TA_CENTER,
                                   spaceBefore=8*mm)

    elements = []

    elements.append(Paragraph("DOG MESSENGER", title_style))
    elements.append(Paragraph("Tienda de Mascotas", subtitle_style))

    info_data = [
        ["Pedido:", order["order_id"]],
        ["Fecha:", order["created_at"]],
        ["Estado:", order["status"]],
    ]
    info_table = Table(info_data, colWidths=[80, 200])
    info_table.setStyle(TableStyle([
        ("FONTSIZE", (0, 0), (-1, -1), 10),
        ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
    ]))
    elements.append(info_table)
    elements.append(Spacer(1, 6*mm))

    items = order.get("items", [])
    table_data = [["Producto", "Cant.", "P. Unit.", "Subtotal"]]
    for item in items:
        subtotal = item["quantity"] * item["unit_price"]
        table_data.append([
            item["product_name"],
            str(item["quantity"]),
            f"S/ {item['unit_price']:.2f}",
            f"S/ {subtotal:.2f}",
        ])
    table_data.append(["", "", "TOTAL:", f"S/ {order['total']:.2f}"])

    t = Table(table_data, colWidths=[200, 50, 80, 80])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#4A90D9")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 10),
        ("ALIGN", (1, 0), (-1, -1), "CENTER"),
        ("ALIGN", (2, 0), (-1, -1), "RIGHT"),
        ("GRID", (0, 0), (-1, -2), 0.5, colors.grey),
        ("LINEABOVE", (0, -1), (-1, -1), 1, colors.black),
        ("FONTNAME", (-2, -1), (-1, -1), "Helvetica-Bold"),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
    ]))
    elements.append(t)

    elements.append(Paragraph("Gracias por su compra!", footer_style))

    doc.build(elements)
    pdf_bytes = buf.getvalue()
    filename = f"comprobante_{order_id}.pdf"
    return filename, pdf_bytes


def generate_receipt(order_id):
    """Genera un recibo de texto formateado para el pedido dado."""
    order = db.get_order(order_id)
    if not order:
        return f"Error: Pedido '{order_id}' no encontrado."

    width = 40
    sep = "=" * width
    dash = "-" * width

    lines = []
    lines.append(sep)
    lines.append(_center("DOG MESSENGER", width))
    lines.append(_center("Tienda de Mascotas", width))
    lines.append(sep)
    lines.append(f"Pedido:  {order['order_id']}")
    lines.append(f"Fecha:   {order['created_at']}")
    lines.append(f"Estado:  {order['status']}")
    lines.append(dash)

    items = order.get("items", [])
    for item in items:
        name = item["product_name"]
        qty = item["quantity"]
        price = item["unit_price"]
        subtotal = qty * price
        lines.append(f"{name}")
        lines.append(f"  {qty} x S/ {price:.2f}          S/ {subtotal:.2f}")

    lines.append(dash)
    lines.append(f"{'TOTAL:':>30} S/ {order['total']:.2f}")
    lines.append(sep)
    lines.append(_center("Gracias por su compra!", width))
    lines.append(sep)

    return "\n".join(lines)


def _center(text, width):
    padding = max(0, width - len(text)) // 2
    return " " * padding + text
