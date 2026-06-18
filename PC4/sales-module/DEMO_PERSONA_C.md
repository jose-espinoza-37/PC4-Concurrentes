# Demo Persona C — Guia paso a paso

## Requisitos previos

1. **Java 17+** instalado (para el servidor)
2. **Python 3.10+** instalado
3. **Dependencias Python:**
   ```bash
   cd PC4/sales-module
   pip install -r requirements.txt
   ```
4. **Servidor compilado:** desde `PC4/server/`
   ```bash
   ./gradlew shadowJar
   ```
5. **Cliente desktop compilado:** desde `PC4/client-desktop/`
   ```bash
   ./gradlew shadowJar
   ```

---

## 1. Levantar el entorno

### Terminal 1 — Servidor
```bash
cd PC4/server
java -jar build/libs/server-all.jar --port 9000
```
Debe mostrar: `Server listening on port 9000` y `File server listening on port 9001`.

### Terminal 2 — Nodo de ventas (bot)
```bash
cd PC4/sales-module
python sales_node.py --host 127.0.0.1 --port 9000
```
Debe mostrar:
```
Conectado al servidor
Autenticado como user_id=X  (o "Registrado y autenticado...")
Nodo de ventas activo. Esperando comandos...
```

### Terminal 3 — Cliente desktop
```bash
cd PC4/client-desktop
java -jar build/libs/client-desktop-all.jar
```
Registrar un usuario (ej: `demo` / `demo123`) e iniciar sesion.

---

## 2. Demo T-C4: Catalogo y precio

Desde el cliente desktop, abrir un chat con el bot de ventas (user ID del bot, mostrado en la terminal 2).

### Con comandos `/`
```
/catalogo
```
Resultado esperado: lista de 10 productos con ID, nombre, precio y stock.

```
/buscar collar
```
Resultado esperado: productos que contengan "collar" en nombre, descripcion o categoria.

```
/precio 1
```
Resultado esperado: nombre, precio y stock del producto con ID 1.

```
/precio collar
```
Resultado esperado: busca por nombre y muestra el primer resultado.

### Con keywords (sin `/`)
```
catalogo
```
Resultado esperado: misma lista de productos (deteccion por keyword).

```
precio collar
```
Resultado esperado: misma respuesta que `/precio collar`.

---

## 3. Demo T-C5: Pedidos

```
/pedir 1 2
```
Resultado esperado:
- Recibo en texto con detalle del pedido (ORD-1001)
- Un archivo PDF (`comprobante_ORD-1001.pdf`) recibido en el chat

```
/mis_pedidos
```
Resultado esperado: lista de pedidos del usuario con estado, total y fecha.

```
/estado ORD-1001
```
Resultado esperado: detalle del pedido con items, cantidades y total.

```
/confirmar ORD-1001
```
Resultado esperado: "Pedido ORD-1001 confirmado."

```
/entregar ORD-1001
```
Resultado esperado: "Pedido ORD-1001 entregado."

---

## 4. Demo T-C6: Comprobante PDF

Al ejecutar `/pedir`, ademas del texto de recibo, el bot genera un PDF con ReportLab y lo envia como archivo al cliente via el protocolo de transferencia (puerto 9001).

Verificar en la terminal del bot:
```
Archivo 'comprobante_ORD-1001.pdf' enviado (1 chunks, XXXX bytes)
```

El cliente desktop debe recibir el archivo y mostrarlo en el chat. Al hacer clic se puede guardar o abrir.

---

## 5. Demo T-C7: Metricas y reportes

### Reporte diario
```
/reporte diario
```
Resultado esperado: resumen del dia con total de pedidos, ingresos, productos mas vendidos y clientes frecuentes. Ademas se envia un PDF con el reporte.

### Reporte semanal
```
/reporte semanal
```
Resultado esperado: resumen de los ultimos 7 dias con top 5 productos y top 5 clientes frecuentes.

### Reporte mensual
```
/reporte mensual
```
Resultado esperado: resumen del mes actual con top 5 productos y top 5 clientes.

Cada reporte genera un PDF (ej: `reporte_semanal_2026-06-18.pdf`) que se envia como archivo.

---

## 6. Demo T-C8: Integracion con chat (keywords)

El bot responde tanto a comandos con `/` como a palabras clave sin prefijo:

| Escribir en chat | Equivale a |
|------------------|------------|
| `catalogo`       | `/catalogo` |
| `ayuda`          | `/ayuda` |
| `buscar hueso`   | `/buscar hueso` |
| `precio 3`       | `/precio 3` |
| `pedir 5`        | `/pedir 5` |
| `pedidos`        | `/mis_pedidos` |
| `reporte`        | `/reporte diario` |

### Mensajes que no son comandos
```
hola, quiero informacion
```
Resultado esperado: el bot responde con un mensaje de bienvenida e indica escribir `/ayuda`. En la terminal del bot se loguea como "Mensaje de cliente (user X): hola, quiero informacion" (passthrough para vendedor humano).

---

## 7. Demo T-C2: Transferencia de archivos

La transferencia de archivos se demuestra automaticamente al generar comprobantes PDF y reportes. El flujo completo es:

1. Bot genera PDF en memoria con ReportLab
2. Bot abre conexion TCP al puerto 9001 (canal de archivos)
3. Bot se autentica en el canal de archivos
4. Bot envia `MSG_FILE` con metadata JSON (transfer_id, filename, size, mime_type, total_chunks)
5. Bot envia N paquetes `FILE_CHUNK` con chunks de 64KB
6. Bot envia `FILE_COMPLETE` con checksum CRC32 del archivo completo
7. Servidor reenvia al destinatario
8. Cliente desktop recibe y muestra el archivo

Para verificar en la terminal del bot:
```
Archivo 'comprobante_ORD-1001.pdf' enviado (1 chunks, 2159 bytes)
```

---

## 8. Demo T-C1: Logica de grupos

### Crear grupo
```
/grupo_crear Equipo Ventas
```
Resultado esperado: "Solicitud de creacion de grupo 'Equipo Ventas' enviada." El servidor responde con ACK y el ID del grupo.

### Agregar miembro
```
/grupo_agregar 1 3
```
(Agrega usuario 3 al grupo 1)
Resultado esperado: "Solicitud para agregar usuario 3 al grupo 1 enviada."

### Mensajes de grupo
Cuando un usuario envia un mensaje al grupo donde esta el bot, el bot procesa el comando y responde en el grupo. En la terminal del bot se muestra:
```
Mensaje de grupo (user X): /catalogo
Respuesta enviada a grupo (user X)
```

### Salir de grupo
```
/grupo_salir 1
```
Resultado esperado: "Solicitud para salir del grupo 1 enviada."

---

## 9. Demo T-C3: Reconexion automatica

1. Con el bot corriendo, detener el servidor (Ctrl+C en Terminal 1)
2. En la terminal del bot debe aparecer:
   ```
   Conexion perdida con el servidor
   Reconectando en 1s...
   Conectando a 127.0.0.1:9000...
   No se pudo conectar: [Errno 111] Connection refused
   Reintentando en 2s...
   ```
3. El backoff aumenta exponencialmente: 1s, 2s, 4s, 8s, 16s, 30s (maximo)
4. Volver a iniciar el servidor. El bot se reconecta automaticamente:
   ```
   Conectado al servidor
   Autenticado como user_id=X
   Nodo de ventas activo. Esperando comandos...
   ```

---

## 10. Comando de ayuda completo

```
/ayuda
```

Muestra todos los comandos disponibles incluyendo los nuevos:
- Catalogo: `/catalogo`, `/buscar`, `/precio`
- Pedidos: `/pedir`, `/mis_pedidos`, `/estado`, `/confirmar`, `/entregar`
- Reportes: `/reporte [diario|semanal|mensual]`
- Grupos: `/grupo_crear`, `/grupo_agregar`, `/grupo_salir`
- Nota sobre keywords sin `/`

---

## Resumen de funcionalidades demostradas

| Tarea | Funcionalidad | Como verificar |
|-------|---------------|----------------|
| T-C1 | Grupos | `/grupo_crear`, `/grupo_agregar`, respuesta a GROUP_MSG |
| T-C2 | Archivos | PDFs enviados via puerto 9001 con chunks 64KB |
| T-C3 | Nodo Python | Protocolo binario, reconexion, cola de mensajes |
| T-C4 | Catalogo | `/catalogo`, `/buscar`, `/precio` |
| T-C5 | Pedidos | `/pedir`, `/mis_pedidos`, `/estado`, `/confirmar`, `/entregar` |
| T-C6 | Comprobantes PDF | PDF generado con ReportLab y enviado como archivo |
| T-C7 | Metricas | `/reporte diario\|semanal\|mensual` con PDF |
| T-C8 | Chat keywords | Escribir `catalogo`, `ayuda`, `precio X` sin `/` |
