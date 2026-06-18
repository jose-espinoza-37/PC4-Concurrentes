"""
catalog.py
Dog Messenger — Modulo de Ventas
CRUD de productos: listar, buscar, verificar stock, decrementar.
Retorna respuestas formateadas para mostrar en el chat.
"""

import db


def handle_catalogo():
    """Comando /catalogo — lista todos los productos."""
    products = db.get_all_products()
    if not products:
        return "No hay productos en el catalogo."

    lines = ["=== CATALOGO DOG MESSENGER ===", ""]
    for p in products:
        stock_label = f"({p['stock']} disponibles)" if p['stock'] > 0 else "(SIN STOCK)"
        lines.append(
            f"  [{p['id']}] {p['name']} — S/ {p['price']:.2f} {stock_label}"
        )
        lines.append(f"      {p['description']}  |  Categoria: {p['category']}")
    lines.append("")
    lines.append(f"Total: {len(products)} productos")
    lines.append("Usa /buscar <nombre> para filtrar o /pedir <id> <cantidad> para ordenar.")
    return "\n".join(lines)


def handle_buscar(query):
    """Comando /buscar <texto> — busca productos por nombre, descripcion o categoria."""
    if not query.strip():
        return "Uso: /buscar <nombre o categoria>"

    products = db.search_products(query.strip())
    if not products:
        return f"No se encontraron productos para '{query.strip()}'."

    lines = [f"=== RESULTADOS PARA '{query.strip().upper()}' ===", ""]
    for p in products:
        stock_label = f"({p['stock']} disp.)" if p['stock'] > 0 else "(SIN STOCK)"
        lines.append(
            f"  [{p['id']}] {p['name']} — S/ {p['price']:.2f} {stock_label}"
        )
    lines.append("")
    lines.append(f"{len(products)} producto(s) encontrado(s).")
    return "\n".join(lines)


def handle_precio(query):
    """Comando /precio <id o nombre> — muestra precio y stock de un producto."""
    query = query.strip()
    if not query:
        return "Uso: /precio <id o nombre>\nEjemplo: /precio 3  o  /precio collar"

    product = None
    try:
        product = db.get_product(int(query))
    except ValueError:
        results = db.search_products(query)
        if results:
            product = results[0]

    if not product:
        return f"Producto '{query}' no encontrado."

    stock_label = f"{product['stock']} disponibles" if product['stock'] > 0 else "SIN STOCK"
    return (
        f"[{product['id']}] {product['name']}\n"
        f"  Precio: S/ {product['price']:.2f}\n"
        f"  Stock:  {stock_label}\n"
        f"  {product['description']}"
    )


def get_product_info(product_id):
    """Obtiene info de un producto por ID."""
    return db.get_product(product_id)


def check_and_decrement_stock(product_id, quantity):
    """Verifica y decrementa stock. Retorna (exito, producto_info)."""
    product = db.get_product(product_id)
    if not product:
        return False, "Producto no encontrado."
    if product["stock"] < quantity:
        return False, f"Stock insuficiente para '{product['name']}'. Disponible: {product['stock']}."
    if not db.decrement_stock(product_id, quantity):
        return False, f"Error al actualizar stock de '{product['name']}'."
    return True, product
