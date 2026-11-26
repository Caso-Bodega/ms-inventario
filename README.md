# ms-inventario

Microservicio de **catálogo y existencias** del caso BodegaNube (JVY0101). Administra los productos,
valida el stock en tiempo real y reserva o descuenta unidades cuando las órdenes entran y salen de bodega.

| | |
|---|---|
| Puerto | `8083` |
| Base de datos | PostgreSQL `bodeganube_inventario` (una base por microservicio) |
| Stack | Java 17, Spring Boot 3.2, Spring Data JPA, Bean Validation, Maven Wrapper |

## Requerimientos que cubre
- **RF-07**: gestión centralizada del catálogo de productos (CRUD).
- **RF-04**: reserva y descuento de stock; una reserva sin stock suficiente se rechaza.

## Arquitectura en capas
```
src/main/java/com/bodeganube/inventario
├── controller/   ProductoController       recibe HTTP, valida con @Valid y delega al service
├── service/      ProductoService          reglas de negocio y transacciones (@Transactional)
├── repository/   ProductoRepository       acceso a datos con Spring Data JPA
├── model/        Producto                 entidad JPA mapeada a la tabla productos
├── dto/          *Request / *Response     contrato JSON de la API, separado de la entidad
└── exception/    GlobalExceptionHandler   todos los errores con el mismo formato JSON
```
Las dependencias se inyectan por constructor y el controller nunca accede directo al repositorio.

## Modelo de datos
Tabla `productos` (entidad `Producto`). Hibernate la crea al iniciar (`ddl-auto: update`).

| Columna | Tipo | Restricciones |
|---|---|---|
| id | bigint | PK, autoincremental |
| sku | varchar | NOT NULL, UNIQUE (clave de negocio que usan las órdenes) |
| nombre | varchar | NOT NULL |
| stock_disponible | integer | NOT NULL |
| stock_reservado | integer | NOT NULL, parte en 0 |

## Endpoints
Base: `http://localhost:8083`. A través de ms-gateway (`http://localhost:8080`) todas las rutas piden JWT; un `COMERCIO` solo puede consultar el catálogo.

| Método | Ruta | Qué hace | Respuestas |
|---|---|---|---|
| GET | `/api/v1/productos` | Lista el catálogo | 200 |
| GET | `/api/v1/productos/{sku}` | Obtiene un producto | 200, 404 |
| POST | `/api/v1/productos` | Crea un producto | 201 con `Location`, 400, 409 si el SKU ya existe |
| PUT | `/api/v1/productos/{sku}` | Cambia el nombre y el stock disponible | 200, 400, 404 |
| DELETE | `/api/v1/productos/{sku}` | Elimina un producto | 204, 404, 409 si tiene unidades reservadas |
| POST | `/api/v1/productos/{sku}/reservar` | Reserva `{"cantidad": n}` | 200, 400, 404, 409 si no hay stock |
| POST | `/api/v1/productos/{sku}/liberar` | Devuelve unidades reservadas a disponible (compensación que usa ms-ordenes) | 200, 400, 404, 409 |
| POST | `/api/v1/productos/{sku}/descontar` | Descuenta unidades reservadas al despachar | 200, 400, 404, 409 |

Todas las respuestas de error tienen el mismo formato:
```json
{
  "timestamp": "2026-10-08T18:30:12.448",
  "status": 409,
  "error": "Conflict",
  "mensaje": "Stock insuficiente para POLERA-M: disponible 20, solicitado 500",
  "ruta": "/api/v1/productos/POLERA-M/reservar"
}
```
En los errores de validación (400) se agrega `detalles`, con el mensaje de cada campo inválido.

## Reservas simultáneas
Con varias réplicas de ms-ordenes y varias Lambdas procesando avisos a la vez, dos reservas del mismo producto
pueden llegar al mismo tiempo. Si las dos leen el mismo stock, una pisa el cálculo de la otra y se vende más de lo
que hay (*lost update*). Por eso `reservar`, `liberar`, `descontar` y `actualizar` leen el producto con
`findBySkuParaActualizar`, que bloquea su fila hasta que termina la transacción (`SELECT ... FOR UPDATE`,
`@Lock(PESSIMISTIC_WRITE)`). Las reservas de un mismo SKU se atienden de a una; las de SKU distintos siguen en
paralelo. La prueba de carga de bodeganube-infra (`pruebas/carga.ps1`, 100 órdenes simultáneas del mismo
producto) deja exactamente 100 unidades reservadas.

## Levantar el servicio desde cero

### 1. Requisitos
- JDK 17 o superior (`java -version`).
- PostgreSQL 14 o superior en `localhost:5432` (usuario y contraseña `postgres` por defecto).
- Git. **No hace falta instalar Maven**: el repositorio trae el Maven Wrapper (`mvnw` y `mvnw.cmd`).

### 2. Clonar el repositorio
```bash
git clone https://github.com/Caso-Bodega/ms-inventario.git
cd ms-inventario
```

### 3. Crear la base de datos
Con psql:
```bash
psql -U postgres -c "CREATE DATABASE bodeganube_inventario;"
```
O en pgAdmin: clic derecho en *Databases* → *Create* → *Database...* → `bodeganube_inventario`.
Las tablas las crea la aplicación al iniciar.

Si no tienes PostgreSQL instalado, puedes usar Docker:
```bash
docker run -d --name bodeganube-db -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16
docker exec bodeganube-db psql -U postgres -c "CREATE DATABASE bodeganube_inventario;"
```

### 4. Compilar, probar y empaquetar
```bash
.\mvnw.cmd clean package    # Windows (PowerShell)
./mvnw clean package        # Linux, macOS o Git Bash
```
Descarga las dependencias, compila, corre las pruebas automatizadas y genera `target/ms-inventario.jar`.

### 5. Ejecutar
```bash
java -jar target/ms-inventario.jar
```
También se puede levantar sin empaquetar con `.\mvnw.cmd spring-boot:run`. Queda escuchando en
`http://localhost:8083`.

### 6. Probar con Postman
Importa `postman/ms-inventario.postman_collection.json` (*Import* → archivo) y usa *Run collection*.
Ejecuta en orden 15 peticiones (crear, consultar, actualizar, reservar, liberar, descontar y eliminar, más los
casos de error 400, 404 y 409) y cada una verifica su código HTTP. Se puede repetir: el SKU cambia en cada
ejecución.

## Configuración
Todo tiene un valor por defecto para desarrollo local y se puede cambiar con variables de entorno:

| Variable | Valor por defecto |
|---|---|
| `PORT` | `8083` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/bodeganube_inventario` |
| `DB_USER` | `postgres` |
| `DB_PASSWORD` | `postgres` |

Ejemplo en PowerShell: `$env:DB_PASSWORD="mi-clave"; java -jar target/ms-inventario.jar`

## Pruebas automatizadas
`.\mvnw.cmd test` corre 16 pruebas que no necesitan base de datos:
- `ProductoServiceTest` (JUnit 5 + Mockito): reglas de stock, SKU duplicado y eliminación con reservas.
- `ProductoControllerTest` (MockMvc): códigos HTTP, validaciones y formato de error.

## Docker
La imagen se construye con el `Dockerfile` de la raíz, en dos etapas:

| Etapa | Imagen base | Qué hace |
|---|---|---|
| build | `eclipse-temurin:17-jdk-alpine` | Baja las dependencias (capa que se reutiliza mientras no cambie `pom.xml`), compila y separa el `.jar` en capas |
| final | `eclipse-temurin:17-jre-alpine` | Solo el JRE y la aplicación en 4 capas, ejecutada por el usuario sin privilegios `bodega` |

Buenas prácticas aplicadas: imagen final de unos 110 MB sin JDK ni código fuente, `.dockerignore` para que el
contexto de build sea chico, usuario no root, `HEALTHCHECK` contra `/actuator/health/liveness`, apagado
ordenado (`server.shutdown: graceful`) y la JVM ajustada al límite de memoria del contenedor
(`-XX:MaxRAMPercentage`).

```bash
docker build -t ms-inventario .
docker run -p 8083:8083 -e DB_URL=jdbc:postgresql://host.docker.internal:5432/bodeganube_inventario ms-inventario
```
Para levantar el sistema completo (5 microservicios + PostgreSQL) se usa `docker compose up -d --build` en
[bodeganube-infra](https://github.com/Caso-Bodega/bodeganube-infra). En Docker Swarm las contraseñas llegan como
secretos en `/run/secrets` y Spring las lee con `spring.config.import=optional:configtree:/run/secrets/`.

## CI/CD (GitHub Actions)
Pipeline en `.github/workflows/ci-cd.yml`:

| Evento | Etapas |
|---|---|
| Pull request a `develop` o `main` | **1. Build y test**: `./mvnw verify` con JDK 17; el reporte de pruebas queda como artefacto |
| Push a `develop` | 1 + **2. Imagen**: publica `ghcr.io/caso-bodega/ms-inventario:develop` en GitHub Container Registry |
| Push a `main` (merge del PR) | 1 + 2 (etiquetas `latest` y `sha-<commit>`) + **3. Despliegue**: `docker service update` en el clúster Swarm de AWS (rolling update con rollback automático) y verificación de `http://<manager>:8080/estado/ms-inventario` |

La etiqueta `sha-<commit>` dice exactamente qué commit corre en el clúster (trazabilidad). La etapa 3 usa los
secretos de la organización `SWARM_HOST` y `SWARM_SSH_KEY`; si no existen, se omite con una advertencia.

## Ramas
`main` tiene la versión entregada, `develop` integra el trabajo en curso y cada cambio entra desde una
rama `feature/...` con un Pull Request hacia `develop`.

## Próximos pasos
- Liberar automáticamente las reservas de órdenes que se rechazan o se eliminan después de reservar.
- Publicar un evento cuando el stock de un producto baja de un mínimo, para reponerlo.
