# Benchmark remoto de lectores GeoParquet: DuckDB vs. Parquetry

[Read in English](README.md)

Este repositorio contiene un benchmark reproducible que compara **DuckDB/httpfs**
y **Parquetry + Tileverse Storage** leyendo GeoParquet remoto desde almacenamiento
de objetos compatible con S3 (Cloudflare R2 en los experimentos originales). Evalúa
los **lectores subyacentes**, no GeoServer WMS/WFS ni implementaciones de DataStore.
Tampoco incluye los experimentos separados con la CLI de Portolan.

El estudio original usa edificios de **Overture Maps para Argentina** (unos 26,77
millones de registros) en tres layouts: `raw`, GeoParquet 1.1 Hilbert/ZSTD y
GeoParquet 2.0 Hilbert/ZSTD. El benchmark principal usa **150 consultas BBOX** y
compara la configuración predeterminada de DuckDB contra Parquetry con Tileverse
`CachingRangeReader` activado por defecto. Mide latencia, cantidad de filas,
percentiles y lecturas delegadas por Parquetry; los diagnósticos estudian el primer
acceso, reutilización de cache, saltos geográficos y memoria del proceso.

## Requisitos

Shell de Linux o macOS, `git`, `curl`, `make`, **Java 25** (incluyendo preview
features), Maven, Python 3.11+ y [uv](https://docs.astral.sh/uv/). AWS CLI es
opcional si las credenciales de R2 se suministran mediante variables de entorno.
Descargar los datasets requiere unos 7 GiB libres; **los benchmarks remotos no
necesitan datos locales**. Se requiere conexión de red para dependencias y R2.

**Parquetry es una dependencia de fuentes** con artefactos snapshot. `make setup`
clona `https://github.com/tileverse-io/parquetry` en `vendor/parquetry`, actualiza
el remoto y compila por defecto el último commit de `origin/main`. El repositorio no
incluye esa biblioteca ni JAR precompilados. Para una corrida reproducible se puede
fijar una rama, tag o commit con `PARQUETRY_REF`, por ejemplo
`PARQUETRY_REF=876687bc865fb3fc0526bd3c7d69d030db6a7d44 make setup`. Setup imprime
el commit resuelto, que debe registrarse junto con los resultados publicados.

## Inicio rápido: comparación remota

```bash
make check
make setup
# Credenciales propias con permiso de sólo lectura:
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."
make smoke
make bench
```

Si existe un perfil de AWS CLI llamado `r2-benchmark`, los scripts intentan obtener
de él las credenciales. En caso contrario deben exportarse. **Nunca se deben
versionar credenciales.**

El bucket y endpoint predeterminados corresponden al estudio original:

```text
R2_BUCKET=geoparquet-benchmarks
R2_ENDPOINT=https://3e5316dddf480d9077bcc2009d58aa10.r2.cloudflarestorage.com
R2_REGION=auto
```

Este es un endpoint de la API S3, **no una URL anónima**: requiere credenciales
válidas para el bucket. Para descargas públicas se registraron estas URLs:

- https://pub-f31f4e8d3c804f2bab542268b9915ad7.r2.dev/raw.parquet
- https://pub-f31f4e8d3c804f2bab542268b9915ad7.r2.dev/optimized-v1.1.parquet
- https://pub-f31f4e8d3c804f2bab542268b9915ad7.r2.dev/optimized-v2.0.parquet

Las URLs provienen de los experimentos originales y **no se revalidaron aquí**.
El endpoint público `r2.dev` puede limitar solicitudes y sirve principalmente para
descargas, no para mediciones controladas de latencia. Sin credenciales del R2
original, ejecute `make data`, suba los objetos a un bucket S3 propio y configure
`R2_BUCKET`, `R2_ENDPOINT` y las credenciales. Consulte `docs/DATA.md`.

## Comandos

| Comando | Propósito | Salida principal |
|---|---|---|
| `make check` | Verificar herramientas y archivos locales | Consola |
| `make setup` | Instalar dependencias y compilar Parquetry y el benchmark | Artefactos Maven |
| `make build` | Recompilar el benchmark Java y el snapshot upstream | `java/target/` |
| `make smoke` | Primer BBOX, sin warmup, ambos lectores y tres layouts | `results/smoke-*.csv` |
| `make bench` | DuckDB predeterminado vs. Parquetry con cache, 150 BBOX por layout | `results/r2-comparable-*.csv` |
| `PARQUETRY_CACHE=off make bench` | Diagnóstico de Parquetry sin cache | `results/r2-comparable-nocache-*.csv` |
| `make report` | Regenerar el resumen de la corrida principal | `results/r2-comparable-summary.csv` |
| `make io-diagnostics` | I/O HTTP/filesystem vs. rangos lógicos en la primera consulta | Consola |
| `make duckdb-cache` | Cache de DuckDB en distintas posiciones | `results/r2-duckdb-cache-diag.csv` |
| `make geographic-jumps` | Tandil → BA → Córdoba → Mendoza → Bariloche → Salta → Tandil | CSV |
| `make cache` | Parquetry con y sin cache sobre regiones repetidas | CSV |
| `make memory` | Métricas RSS/heap/cache durante saltos geográficos | CSV |
| `make data` | Descargar los tres GeoParquet públicos | `data/` |
| `make optimize` | Recrear layouts desde `data/raw/raw.parquet` | `data/optimized/` |
| `make all` | Comparación principal y diagnósticos | `results/` |

Corrida rápida:

```bash
WARMUPS=0 LIMIT=3 make bench
```

Workload persistente completo:

```bash
WARMUPS=2 LIMIT=0 make bench
```

`LIMIT=0` usa las 150 filas. `WARMUPS` repite el workload completo antes de la
pasada medida. Se mantiene un proceso y lector persistente por motor y layout; no
son benchmarks que creen un proceso frío por consulta.

`PARQUETRY_CACHE=on` es el valor predeterminado. Por cada layout se crean una sola
instancia de Tileverse `CachingRangeReader` y un solo `ParquetSource`, reutilizados
durante todos los warmups y consultas medidas. `PARQUETRY_CACHE=off` evita el
wrapper y escribe archivos de diagnóstico separados, por lo que no sobrescribe
silenciosamente los resultados cacheados. Los valores inválidos fallan de inmediato.
DuckDB conserva sus valores predeterminados de `httpfs`; el benchmark no modifica
su configuración de cache.

Con `WARMUPS=0`, la pasada medida se etiqueta `first_access_session`, pero esto no
garantiza que los caches del sistema operativo, red, CDN u otros caches compartidos
estén vacíos. Con uno o más warmups se etiqueta `persistent_warm_session`.

### Salidas e interpretación

`results/r2-comparable-runs.csv` registra configuración del lector, modo del
benchmark/cache, lifecycle, warmups, cantidad de filas y latencia. Las filas de
Parquetry incluyen llamadas y bytes que alcanzan el reader físico **debajo** del
cache opcional. Son operaciones delegadas de rango, no un conteo verificado de
solicitudes HTTP.

El resumen agrupa consultas pequeñas, medianas y grandes, calcula
mediana/media/p95/p99 y valida por consulta las filas obtenidas por DuckDB y
Parquetry. Las diferencias se advierten y etiquetan; no se ocultan. Los CSV
históricos sin metadata de cache se marcan `legacy_unspecified` y no sustentan
afirmaciones sobre una comparación alineada por cache.

El resultado principal compara dos configuraciones completas —DuckDB con sus
valores predeterminados y Parquetry con cache Tileverse—, no motores de decodificación
aislados ni implementaciones idénticas de cache. Los resultados originales en
`results/reference/` son históricos y no se regeneran con `make report`.

**Importante:** DuckDB compara el campo `bbox` almacenado, mientras Parquetry usa
`geometry.bboxIntersects`; pueden diferir algunas entidades de borde. Los contadores
de I/O tampoco son directamente equivalentes: DuckDB puede registrar operaciones
físicas HTTP/filesystem y Parquetry llamadas lógicas de rango. No deben presentarse
como predicados ni instrumentaciones idénticas.

Consulte [Metodología](docs/METHODOLOGY.md) y
[Datos y credenciales](docs/DATA.md).

## Limitaciones

- No mide GeoServer de extremo a extremo; mide bibliotecas lectoras.
- Los resultados dependen de región R2, red, versiones, caches, JVM/Python y orden.
- El costo de la primera consulta debe separarse del comportamiento estable.
- GeoParquet-IO prepara los datos; la comparación remota es DuckDB vs. Parquetry.
- El código actual requiere Java 25 con preview features.
- Ejecute `make smoke` antes de publicar mediciones nuevas.

## Solicitudes concurrentes (experimental)

`make bench-concurrent` ejecuta **un lector persistente por motor**: DuckDB comparte
la base entre cursores y Parquetry comparte un `ParquetSource` entre threads. Informa
throughput (QPS), latencias p50/p95/p99, RSS máximo en Linux y filas totales. Es una
medición distinta del estudio secuencial. El runner de Parquetry usa un
`CachingRangeReader` compartido; el cache forma parte de la configuración medida.

```bash
# Requiere las mismas credenciales S3 y make setup
SCENARIO=warm LEVELS="1 2 4 8 16" QUERIES=40 make bench-concurrent
SCENARIO=mixed LEVELS="1 2 4 8" QUERIES=40 make bench-concurrent
SCENARIO=cold LEVELS="1 2 4" QUERIES=20 make bench-concurrent
```

Cada invocación directa sobrescribe `results/r2-concurrent.csv`. `OBJECT` selecciona
el dataset (`optimized-v1.1.parquet` por defecto); también pueden configurarse
`WORKLOAD`, `WARMUP`, `OUT`, `LEVELS` y `QUERIES`.

**Interpretación:** `warm` precarga seis BBOX y los repite; `mixed` precarga seis y
recorre todo el workload; `cold` omite warmup, aunque caches externos pueden seguir
calientes. Cada nivel de concurrencia usa un proceso nuevo. Es una prueba *closed
loop* con cantidad fija de consultas, no un generador de tasa fija. La latencia
incluye la ejecución en el worker, no la espera en la cola. No compare estas cifras
directamente con resultados secuenciales sin describir workload y cache.

## Suite concurrente repetida

Cada escenario ejecuta **tres corridas independientes por defecto** y conserva un
CSV por corrida más un resumen de medianas. La suite completa produce nueve corridas.

```bash
make bench-concurrent-warm
make bench-concurrent-mixed
make bench-concurrent-cold
# Todos los escenarios y archivo comprimido:
make bench-concurrent-report
```

| Escenario | Workers predeterminados | Consultas por nivel | Patrón |
|---|---|---:|---|
| `warm` | 1, 2, 4, 8, 16 | 150 | Seis ventanas repetidas, con warmup explícito |
| `mixed` | 1, 2, 4, 8, 16 | 150 | Workload completo, primeras seis con warmup |
| `cold` | 1, 2, 4, 8 | 40 | Sin warmup explícito; no garantiza caches externos fríos |

Overrides disponibles: `RUNS=5`, `LEVELS="1 2 4"`, `QUERIES=100`,
`REPORT_DIR=results/custom` y `OBJECT=raw.parquet`. Cada corrida inicia procesos
nuevos por motor y nivel. Los CSV anteriores del escenario se reemplazan para no
contaminar resúmenes con datos viejos.

Las salidas están en `results/report-concurrency/`: `<scenario>-run-<N>.csv` y
`<scenario>-summary.csv`. `make bench-concurrent-report` también crea
`results/geoparquet-concurrency-report-data.tar.gz`. El resumen usa la mediana de
las métricas de cada corrida, incluidos p95/p99; no calcula percentiles agrupando
todas las solicitudes.

## GitHub Actions

`.github/workflows/benchmark.yml` verifica sintaxis shell/Python y pruebas de reporte
en pushes y pull requests. Los benchmarks remotos completos son manuales porque
requieren credenciales, red y tiempo considerable. Configure los secrets
`R2_ACCESS_KEY_ID` y `R2_SECRET_ACCESS_KEY`; las variables opcionales son
`R2_BUCKET`, `R2_ENDPOINT` y `R2_REGION`. El job instala Java 25, prepara las
dependencias, ejecuta smoke y los escenarios seleccionados, y publica los CSV como
artefacto.

Los runners hospedados por GitHub no garantizan hardware estable. Para cifras
comparables use una clase de runner constante o un runner dedicado autohospedado.
