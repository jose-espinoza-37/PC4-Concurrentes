"""
metrics.py
Dog Messenger — Modulo de Ventas
Reportes diarios de ventas: ingresos, pedidos, producto mas vendido.
"""

from datetime import date
import db


def handle_reporte(args=""):
    """
    Comando /reporte [diario|fecha]
    Genera reporte de ventas del dia actual o de una fecha especifica.
    """
    target = args.strip()

    if target and target != "diario":
        # Intentar interpretar como fecha YYYY-MM-DD
        target_date = target
    else:
        target_date = date.today().isoformat()

    summary = db.get_daily_summary(target_date)

    # Guardar en historico
    db.save_daily_summary(summary)

    width = 40
    sep = "=" * width
    dash = "-" * width

    lines = []
    lines.append(sep)
    lines.append(_center("REPORTE DE VENTAS", width))
    lines.append(sep)
    lines.append(f"  Fecha:             {summary['date']}")
    lines.append(f"  Total pedidos:     {summary['total_orders']}")
    lines.append(f"  Ingresos totales:  S/ {summary['total_revenue']:.2f}")
    lines.append(f"  Producto estrella: {summary['top_product']}")
    lines.append(sep)

    return "\n".join(lines)


def _center(text, width):
    padding = max(0, width - len(text)) // 2
    return " " * padding + text
