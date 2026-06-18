"""
metrics.py
Dog Messenger — Modulo de Ventas
Reportes de ventas: diario, semanal, mensual.
Incluye productos mas vendidos y clientes frecuentes.
Genera PDF con ReportLab cuando esta disponible.
"""

from datetime import date, timedelta
from io import BytesIO
import db

try:
    from reportlab.lib.pagesizes import A4
    from reportlab.lib import colors
    from reportlab.lib.units import mm
    from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib.enums import TA_CENTER
    _HAS_REPORTLAB = True
except ImportError:
    _HAS_REPORTLAB = False


def handle_reporte(args=""):
    """
    Comando /reporte [diario|semanal|mensual|fecha]
    Retorna (texto, pdf_result_or_none).
    pdf_result es (filename, pdf_bytes) si ReportLab esta disponible.
    """
    target = args.strip().lower()

    if target in ("semanal", "semana"):
        return _report_weekly()
    elif target in ("mensual", "mes"):
        return _report_monthly()
    else:
        if target and target != "diario":
            target_date = target
        else:
            target_date = date.today().isoformat()
        return _report_daily(target_date)


def _report_daily(target_date):
    summary = db.get_daily_summary(target_date)
    db.save_daily_summary(summary)

    top_products = db.get_top_products(target_date, target_date, 5)
    frequent = db.get_frequent_clients(target_date, target_date, 5)

    text = _format_report("REPORTE DIARIO", summary["date"], summary, top_products, frequent)
    pdf = _generate_pdf("diario", summary["date"], summary, top_products, frequent)
    return text, pdf


def _report_weekly():
    today = date.today().isoformat()
    start = (date.today() - timedelta(days=6)).isoformat()
    summary = db.get_weekly_summary(today)
    top_products = db.get_top_products(start, today, 5)
    frequent = db.get_frequent_clients(start, today, 5)

    text = _format_report("REPORTE SEMANAL", summary["period"], summary, top_products, frequent)
    pdf = _generate_pdf("semanal", summary["period"], summary, top_products, frequent)
    return text, pdf


def _report_monthly():
    today = date.today().isoformat()
    month_start = today[:8] + "01"
    summary = db.get_monthly_summary(today)
    top_products = db.get_top_products(month_start, today, 5)
    frequent = db.get_frequent_clients(month_start, today, 5)

    text = _format_report("REPORTE MENSUAL", summary["period"], summary, top_products, frequent)
    pdf = _generate_pdf("mensual", summary["period"], summary, top_products, frequent)
    return text, pdf


def _format_report(title, period, summary, top_products, frequent_clients):
    width = 44
    sep = "=" * width
    dash = "-" * width

    lines = [sep, _center(title, width), sep]
    lines.append(f"  Periodo:           {period}")
    lines.append(f"  Total pedidos:     {summary['total_orders']}")
    lines.append(f"  Ingresos totales:  S/ {summary['total_revenue']:.2f}")
    lines.append(dash)

    if top_products:
        lines.append("  PRODUCTOS MAS VENDIDOS:")
        for i, p in enumerate(top_products, 1):
            lines.append(f"    {i}. {p['name']} — {p['qty']} uds. (S/ {p['revenue']:.2f})")
    else:
        lines.append("  Sin ventas en este periodo.")
    lines.append(dash)

    if frequent_clients:
        lines.append("  CLIENTES FRECUENTES:")
        for i, c in enumerate(frequent_clients, 1):
            lines.append(f"    {i}. Usuario #{c['user_id']} — {c['order_count']} pedidos (S/ {c['total_spent']:.2f})")
    lines.append(sep)
    return "\n".join(lines)


def _generate_pdf(report_type, period, summary, top_products, frequent_clients):
    if not _HAS_REPORTLAB:
        return None

    buf = BytesIO()
    doc = SimpleDocTemplate(buf, pagesize=A4,
                            topMargin=20*mm, bottomMargin=20*mm,
                            leftMargin=20*mm, rightMargin=20*mm)
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle("RTitle", parent=styles["Title"], fontSize=16, spaceAfter=2*mm)
    sub_style = ParagraphStyle("RSub", parent=styles["Normal"], fontSize=11,
                                alignment=TA_CENTER, spaceAfter=6*mm)
    section_style = ParagraphStyle("RSec", parent=styles["Heading3"], fontSize=12, spaceBefore=4*mm)

    elements = []
    elements.append(Paragraph(f"REPORTE {report_type.upper()}", title_style))
    elements.append(Paragraph(f"Periodo: {period}", sub_style))

    info = [
        ["Total pedidos:", str(summary["total_orders"])],
        ["Ingresos totales:", f"S/ {summary['total_revenue']:.2f}"],
    ]
    it = Table(info, colWidths=[140, 160])
    it.setStyle(TableStyle([
        ("FONTSIZE", (0, 0), (-1, -1), 11),
        ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]))
    elements.append(it)

    if top_products:
        elements.append(Paragraph("Productos mas vendidos", section_style))
        tp_data = [["#", "Producto", "Cantidad", "Ingresos"]]
        for i, p in enumerate(top_products, 1):
            tp_data.append([str(i), p["name"], str(p["qty"]), f"S/ {p['revenue']:.2f}"])
        tp = Table(tp_data, colWidths=[30, 200, 70, 80])
        tp.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#4A90D9")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
            ("FONTSIZE", (0, 0), (-1, -1), 10),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("ALIGN", (2, 0), (-1, -1), "CENTER"),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
        ]))
        elements.append(tp)

    if frequent_clients:
        elements.append(Paragraph("Clientes frecuentes", section_style))
        fc_data = [["#", "Usuario", "Pedidos", "Gasto total"]]
        for i, c in enumerate(frequent_clients, 1):
            fc_data.append([str(i), f"#{c['user_id']}", str(c["order_count"]),
                            f"S/ {c['total_spent']:.2f}"])
        fc = Table(fc_data, colWidths=[30, 100, 70, 100])
        fc.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#4A90D9")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
            ("FONTSIZE", (0, 0), (-1, -1), 10),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
            ("ALIGN", (2, 0), (-1, -1), "CENTER"),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
        ]))
        elements.append(fc)

    doc.build(elements)
    filename = f"reporte_{report_type}_{date.today().isoformat()}.pdf"
    return filename, buf.getvalue()


def _center(text, width):
    padding = max(0, width - len(text)) // 2
    return " " * padding + text
