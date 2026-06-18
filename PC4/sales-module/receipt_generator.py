"""
receipt_generator.py
Dog Messenger — Modulo de Ventas
Genera recibos en texto plano formateado (ASCII art).
"""

from datetime import datetime
import db


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
    """Centra texto en el ancho dado."""
    padding = max(0, width - len(text)) // 2
    return " " * padding + text
