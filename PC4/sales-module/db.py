"""
db.py
Dog Messenger — Modulo de Ventas
Capa de persistencia SQLite para catalogo, pedidos y metricas.

Tablas:
  - products: catalogo de productos con stock
  - orders: pedidos con estado (PENDING, CONFIRMED, DELIVERED)
  - order_items: lineas de detalle de cada pedido
  - daily_sales: resumen diario de ventas

Thread-safe: todas las operaciones usan un Lock global.
"""

import sqlite3
import threading
from datetime import datetime, date, timedelta

DB_FILE = "sales.db"

_lock = threading.Lock()


def _connect():
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db():
    """Crea las tablas e inserta productos de ejemplo si la BD esta vacia."""
    with _lock:
        conn = _connect()
        c = conn.cursor()

        c.executescript("""
        CREATE TABLE IF NOT EXISTS products (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            name        TEXT    NOT NULL UNIQUE,
            description TEXT    DEFAULT '',
            price       REAL    NOT NULL CHECK(price > 0),
            stock       INTEGER NOT NULL DEFAULT 0 CHECK(stock >= 0),
            category    TEXT    DEFAULT 'general',
            image       TEXT    DEFAULT ''
        );

        CREATE TABLE IF NOT EXISTS orders (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            order_id    TEXT    NOT NULL UNIQUE,
            user_id     INTEGER NOT NULL,
            status      TEXT    NOT NULL DEFAULT 'PENDING'
                                CHECK(status IN ('PENDING','CONFIRMED','DELIVERED','CANCELLED')),
            total       REAL    NOT NULL DEFAULT 0,
            created_at  TEXT    NOT NULL
        );

        CREATE TABLE IF NOT EXISTS order_items (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            order_id    TEXT    NOT NULL REFERENCES orders(order_id),
            product_id  INTEGER NOT NULL REFERENCES products(id),
            quantity    INTEGER NOT NULL CHECK(quantity > 0),
            unit_price  REAL    NOT NULL
        );

        CREATE TABLE IF NOT EXISTS daily_sales (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            sale_date     TEXT    NOT NULL UNIQUE,
            total_revenue REAL    NOT NULL DEFAULT 0,
            total_orders  INTEGER NOT NULL DEFAULT 0,
            top_product   TEXT    DEFAULT ''
        );

        CREATE INDEX IF NOT EXISTS idx_orders_user   ON orders(user_id);
        CREATE INDEX IF NOT EXISTS idx_orders_status  ON orders(status);
        CREATE INDEX IF NOT EXISTS idx_order_items_oid ON order_items(order_id);
        CREATE INDEX IF NOT EXISTS idx_daily_date     ON daily_sales(sale_date);
        """)

        # Migracion: agregar columna image si no existe
        try:
            c.execute("SELECT image FROM products LIMIT 1")
        except sqlite3.OperationalError:
            c.execute("ALTER TABLE products ADD COLUMN image TEXT DEFAULT ''")

        # Insertar productos de ejemplo si la tabla esta vacia
        count = c.execute("SELECT COUNT(*) FROM products").fetchone()[0]
        if count == 0:
            sample_products = [
                ("Collar para perro", "Collar ajustable de nylon", 15.99, 50, "accesorios", "collar.jpg"),
                ("Correa retractil", "Correa de 5 metros", 24.99, 30, "accesorios", "correa.jpg"),
                ("Alimento premium 10kg", "Alimento seco para perro adulto", 45.50, 100, "alimento", "alimento_premium.jpg"),
                ("Alimento cachorro 5kg", "Alimento seco para cachorros", 32.00, 80, "alimento", "alimento_cachorro.jpg"),
                ("Juguete hueso", "Hueso de goma resistente", 8.99, 200, "juguetes", "hueso.jpg"),
                ("Pelota resistente", "Pelota de caucho para masticar", 6.50, 150, "juguetes", "pelota.jpg"),
                ("Cama ortopedica M", "Cama tamano mediano", 55.00, 25, "descanso", "cama.jpg"),
                ("Shampoo antipulgas", "Shampoo 500ml", 12.99, 60, "higiene", "shampoo.jpg"),
                ("Plato doble acero", "Plato comedero y bebedero", 18.50, 40, "accesorios", "plato.jpg"),
                ("Arnes deportivo", "Arnes acolchado talla M", 29.99, 35, "accesorios", "arnes.jpg"),
            ]
            c.executemany(
                "INSERT INTO products (name, description, price, stock, category, image) VALUES (?,?,?,?,?,?)",
                sample_products
            )

        conn.commit()
        conn.close()


# -- Productos ---------------------------------------------------------------

def get_all_products():
    with _lock:
        conn = _connect()
        rows = conn.execute(
            "SELECT id, name, description, price, stock, category FROM products ORDER BY id"
        ).fetchall()
        conn.close()
        return [dict(r) for r in rows]


def search_products(query):
    with _lock:
        conn = _connect()
        rows = conn.execute(
            "SELECT id, name, description, price, stock, category FROM products "
            "WHERE name LIKE ? OR description LIKE ? OR category LIKE ?",
            (f"%{query}%", f"%{query}%", f"%{query}%")
        ).fetchall()
        conn.close()
        return [dict(r) for r in rows]


def get_product(product_id):
    with _lock:
        conn = _connect()
        row = conn.execute(
            "SELECT id, name, description, price, stock, category FROM products WHERE id = ?",
            (product_id,)
        ).fetchone()
        conn.close()
        return dict(row) if row else None


def decrement_stock(product_id, quantity):
    """Decrementa stock. Retorna True si habia suficiente stock."""
    with _lock:
        conn = _connect()
        row = conn.execute("SELECT stock FROM products WHERE id = ?", (product_id,)).fetchone()
        if not row or row["stock"] < quantity:
            conn.close()
            return False
        conn.execute(
            "UPDATE products SET stock = stock - ? WHERE id = ?",
            (quantity, product_id)
        )
        conn.commit()
        conn.close()
        return True


# -- Pedidos ------------------------------------------------------------------

def get_next_order_id():
    """Genera el siguiente ID de pedido tipo ORD-1001."""
    with _lock:
        conn = _connect()
        row = conn.execute("SELECT MAX(id) as max_id FROM orders").fetchone()
        next_num = 1001 + (row["max_id"] if row["max_id"] else 0)
        conn.close()
        return f"ORD-{next_num}"


def create_order(order_id, user_id, items, total):
    """
    Crea un pedido.
    items: lista de dicts {product_id, quantity, unit_price}
    """
    with _lock:
        conn = _connect()
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        conn.execute(
            "INSERT INTO orders (order_id, user_id, status, total, created_at) VALUES (?,?,?,?,?)",
            (order_id, user_id, "PENDING", total, now)
        )
        for item in items:
            conn.execute(
                "INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?,?,?,?)",
                (order_id, item["product_id"], item["quantity"], item["unit_price"])
            )
        conn.commit()
        conn.close()


def update_order_status(order_id, new_status):
    """Actualiza el estado de un pedido. Retorna True si se actualizo."""
    with _lock:
        conn = _connect()
        cur = conn.execute(
            "UPDATE orders SET status = ? WHERE order_id = ?",
            (new_status, order_id)
        )
        conn.commit()
        updated = cur.rowcount > 0
        conn.close()
        return updated


def get_order(order_id):
    with _lock:
        conn = _connect()
        order = conn.execute(
            "SELECT * FROM orders WHERE order_id = ?", (order_id,)
        ).fetchone()
        if not order:
            conn.close()
            return None
        items = conn.execute(
            "SELECT oi.*, p.name as product_name FROM order_items oi "
            "JOIN products p ON oi.product_id = p.id WHERE oi.order_id = ?",
            (order_id,)
        ).fetchall()
        conn.close()
        result = dict(order)
        result["items"] = [dict(i) for i in items]
        return result


def get_orders_by_user(user_id):
    with _lock:
        conn = _connect()
        rows = conn.execute(
            "SELECT order_id, status, total, created_at FROM orders "
            "WHERE user_id = ? ORDER BY created_at DESC",
            (user_id,)
        ).fetchall()
        conn.close()
        return [dict(r) for r in rows]


# -- Metricas ----------------------------------------------------------------

def get_daily_summary(target_date=None):
    """Calcula resumen de ventas del dia dado (o hoy)."""
    if target_date is None:
        target_date = date.today().isoformat()
    with _lock:
        conn = _connect()
        row = conn.execute(
            "SELECT COUNT(*) as total_orders, COALESCE(SUM(total),0) as total_revenue "
            "FROM orders WHERE DATE(created_at) = ?",
            (target_date,)
        ).fetchone()

        top = conn.execute(
            "SELECT p.name, SUM(oi.quantity) as qty FROM order_items oi "
            "JOIN orders o ON oi.order_id = o.order_id "
            "JOIN products p ON oi.product_id = p.id "
            "WHERE DATE(o.created_at) = ? "
            "GROUP BY p.name ORDER BY qty DESC LIMIT 1",
            (target_date,)
        ).fetchone()

        conn.close()
        return {
            "date": target_date,
            "total_orders": row["total_orders"],
            "total_revenue": row["total_revenue"],
            "top_product": top["name"] if top else "N/A"
        }


def save_daily_summary(summary):
    with _lock:
        conn = _connect()
        conn.execute(
            "INSERT OR REPLACE INTO daily_sales (sale_date, total_revenue, total_orders, top_product) "
            "VALUES (?, ?, ?, ?)",
            (summary["date"], summary["total_revenue"], summary["total_orders"], summary["top_product"])
        )
        conn.commit()
        conn.close()


def get_weekly_summary(target_date=None):
    if target_date is None:
        target_date = date.today().isoformat()
    start = (date.fromisoformat(target_date) - timedelta(days=6)).isoformat()
    with _lock:
        conn = _connect()
        row = conn.execute(
            "SELECT COUNT(*) as total_orders, COALESCE(SUM(total),0) as total_revenue "
            "FROM orders WHERE DATE(created_at) BETWEEN ? AND ?",
            (start, target_date)
        ).fetchone()
        conn.close()
        return {
            "period": f"{start} a {target_date}",
            "total_orders": row["total_orders"],
            "total_revenue": row["total_revenue"],
        }


def get_monthly_summary(target_date=None):
    if target_date is None:
        target_date = date.today().isoformat()
    month_start = target_date[:8] + "01"
    with _lock:
        conn = _connect()
        row = conn.execute(
            "SELECT COUNT(*) as total_orders, COALESCE(SUM(total),0) as total_revenue "
            "FROM orders WHERE DATE(created_at) BETWEEN ? AND ?",
            (month_start, target_date)
        ).fetchone()
        conn.close()
        return {
            "period": f"{month_start} a {target_date}",
            "total_orders": row["total_orders"],
            "total_revenue": row["total_revenue"],
        }


def get_top_products(period_start, period_end, limit=5):
    with _lock:
        conn = _connect()
        rows = conn.execute(
            "SELECT p.name, SUM(oi.quantity) as qty, SUM(oi.quantity * oi.unit_price) as revenue "
            "FROM order_items oi "
            "JOIN orders o ON oi.order_id = o.order_id "
            "JOIN products p ON oi.product_id = p.id "
            "WHERE DATE(o.created_at) BETWEEN ? AND ? "
            "GROUP BY p.name ORDER BY qty DESC LIMIT ?",
            (period_start, period_end, limit)
        ).fetchall()
        conn.close()
        return [dict(r) for r in rows]


def get_frequent_clients(period_start, period_end, limit=5):
    with _lock:
        conn = _connect()
        rows = conn.execute(
            "SELECT user_id, COUNT(*) as order_count, SUM(total) as total_spent "
            "FROM orders WHERE DATE(created_at) BETWEEN ? AND ? "
            "GROUP BY user_id ORDER BY order_count DESC LIMIT ?",
            (period_start, period_end, limit)
        ).fetchall()
        conn.close()
        return [dict(r) for r in rows]
