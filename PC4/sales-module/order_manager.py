"""
order_manager.py
Dog Messenger — Modulo de Ventas
Gestion de pedidos: crear, cambiar estado, consultar.
Genera IDs tipo ORD-1001.
"""

import db
import catalog
import receipt_generator


def handle_pedir(user_id, args):
    """
    Comando /pedir <product_id> [cantidad]
    Crea un pedido con un solo producto (simplificado).
    """
    parts = args.strip().split()
    if not parts:
        return "Uso: /pedir <id_producto> [cantidad]\nEjemplo: /pedir 3 2"

    try:
        product_id = int(parts[0])
    except ValueError:
        return "El ID del producto debe ser un numero. Ejemplo: /pedir 3"

    quantity = 1
    if len(parts) > 1:
        try:
            quantity = int(parts[1])
            if quantity <= 0:
                return "La cantidad debe ser mayor a 0."
        except ValueError:
            return "La cantidad debe ser un numero. Ejemplo: /pedir 3 2"

    # Verificar stock y decrementar
    ok, result = catalog.check_and_decrement_stock(product_id, quantity)
    if not ok:
        return result

    product = result
    unit_price = product["price"]
    total = unit_price * quantity

    # Crear pedido
    order_id = db.get_next_order_id()
    items = [{
        "product_id": product_id,
        "quantity": quantity,
        "unit_price": unit_price
    }]
    db.create_order(order_id, user_id, items, total)

    # Generar recibo
    receipt = receipt_generator.generate_receipt(order_id)
    return receipt


def handle_mis_pedidos(user_id):
    """Comando /mis_pedidos — lista pedidos del usuario."""
    orders = db.get_orders_by_user(user_id)
    if not orders:
        return "No tienes pedidos registrados."

    lines = ["=== MIS PEDIDOS ===", ""]
    for o in orders:
        status_icon = {
            "PENDING": "[*]",
            "CONFIRMED": "[+]",
            "DELIVERED": "[v]",
            "CANCELLED": "[x]"
        }.get(o["status"], "[ ]")
        lines.append(
            f"  {status_icon} {o['order_id']} — S/ {o['total']:.2f} — {o['status']} — {o['created_at']}"
        )
    lines.append("")
    lines.append(f"Total: {len(orders)} pedido(s)")
    lines.append("Usa /estado <ORD-XXXX> para ver detalles.")
    return "\n".join(lines)


def handle_estado(args):
    """Comando /estado <ORD-XXXX> — muestra detalles de un pedido."""
    order_id = args.strip().upper()
    if not order_id:
        return "Uso: /estado <ORD-XXXX>\nEjemplo: /estado ORD-1001"

    order = db.get_order(order_id)
    if not order:
        return f"Pedido '{order_id}' no encontrado."

    lines = [f"=== DETALLE PEDIDO {order_id} ===", ""]
    lines.append(f"  Estado:  {order['status']}")
    lines.append(f"  Fecha:   {order['created_at']}")
    lines.append(f"  Items:")
    for item in order.get("items", []):
        lines.append(
            f"    - {item['product_name']} x{item['quantity']} @ S/ {item['unit_price']:.2f}"
        )
    lines.append(f"  Total:   S/ {order['total']:.2f}")
    return "\n".join(lines)


def confirm_order(order_id):
    """Cambia estado de PENDING a CONFIRMED."""
    order = db.get_order(order_id)
    if not order:
        return f"Pedido '{order_id}' no encontrado."
    if order["status"] != "PENDING":
        return f"Pedido '{order_id}' no esta pendiente (estado actual: {order['status']})."
    db.update_order_status(order_id, "CONFIRMED")
    return f"Pedido {order_id} confirmado."


def deliver_order(order_id):
    """Cambia estado de CONFIRMED a DELIVERED."""
    order = db.get_order(order_id)
    if not order:
        return f"Pedido '{order_id}' no encontrado."
    if order["status"] != "CONFIRMED":
        return f"Pedido '{order_id}' no esta confirmado (estado actual: {order['status']})."
    db.update_order_status(order_id, "DELIVERED")
    return f"Pedido {order_id} entregado."
