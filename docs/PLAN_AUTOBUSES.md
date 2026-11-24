# Cómo añadir EMT y autobuses interurbanos a SleepTrain

Propuesta de diseño técnico, 23 de noviembre de 2026. SleepTrain calcula los trayectos en el móvil. La misma aplicación podría buscar paradas de autobús, proponer itinerarios combinados y seguir el recorrido elegido para avisar antes de bajar.

## Datos de partida

| Red | Fuente | Primera integración |
| --- | --- | --- |
| EMT Madrid | [GTFS oficial de EMT Madrid](https://datos.emtmadrid.es/es/dataset/gtfs-de-emtmadrid) | Importar las paradas, líneas, variantes, calendarios y recorridos. |
| Interurbanos de la Comunidad de Madrid | [Datos Abiertos del CRTM](https://datos.crtm.es/?lang=es), apartado Autobuses Interurbanos | Descargar el GTFS de la red interurbana y conservar la agencia de cada línea. |

Usaría una sola fuente base por red para evitar duplicar servicios. Guardaría procedencia, fecha de descarga y vigencia con cada versión del catálogo. Para los datos del CRTM mantendría la atribución «Powered by CRTM» y explicaría qué datos se han transformado. [Licencia de uso del CRTM](https://www.crtm.es/licencia-de-uso/)

## Del GTFS al buscador y a la ruta

1. **Importar paradas y recorridos.** Ampliaría el generador de catálogo para leer `stops.txt`, `routes.txt`, `trips.txt` y `stop_times.txt`. Usaría `calendar.txt`, `calendar_dates.txt` y `frequencies.txt` cuando estén presentes, y conservaría `shapes.txt` para el seguimiento. Identificaría paradas y líneas con el prefijo de su fuente, por ejemplo `emt:<stop_id>` y `interurbano:<stop_id>`. El orden de `stop_sequence`, las restricciones de subida y bajada y el calendario determinan qué trayectos se pueden ofrecer. [Referencia GTFS Schedule](https://gtfs.org/documentation/schedule/reference/)
2. **Separar las marquesinas del lugar que se busca.** «Moncloa» puede agrupar distintas entradas y paradas, pero cada dársena o marquesina conserva sus coordenadas, líneas y sentido. Agruparía con `parent_station` cuando sea fiable y con correspondencias verificadas para los intercambiadores. Dos paradas del mismo nombre en aceras opuestas pueden seguir siendo puntos de embarque distintos.
3. **Modelar recorridos dirigidos.** El planificador ferroviario actual conecta estaciones vecinas en ambos sentidos. Los autobuses tendrían aristas según el orden real de cada variante y sentido. Una ida y su vuelta pueden pasar por calles y paradas distintas. Agruparía viajes con la misma secuencia de paradas para que el catálogo y la búsqueda sigan siendo ligeros.
4. **Representar el transbordo a pie.** Incorporaría enlaces publicados en `transfers.txt` o conexiones peatonales comprobadas. El buscador mostraría nombre, municipio cuando ayude a desambiguar, código de parada y líneas. Cada alternativa indicaría línea, sentido, parada de subida, parada de bajada, tiempo a pie y duración. Si hay horarios válidos, compararía también las esperas previstas según el día y la hora de salida. Las rutinas guardarían la ruta de ida y la de vuelta de forma independiente.

## Cómo sabría cuándo avisar

El cambio principal estaría en el seguimiento. Para el autobús, proyectaría las muestras GPS sobre la geometría de su recorrido en vez de unir paradas con rectas. Con `shape_dist_traveled` o una proyección ordenada de las paradas podría distinguir cruces, rotondas y trayectos de ida y vuelta que pasan cerca. Contrastaría varias muestras con el sentido elegido antes de avanzar una parada.

En una rutina, el teléfono esperaría cerca de la parada de salida y buscaría movimiento coherente con el tramo elegido. Adaptaría los umbrales a autobuses lentos y paradas próximas. Si dos líneas comparten un tramo, un coche recorre la misma calle o dos variantes siguen siendo compatibles, la ubicación por sí sola no identifica el vehículo. En ese caso conservaría el estado de espera y ofrecería confirmación de embarque si fuese necesario.

El aviso se basaría en la progresión por el itinerario y en la parada donde se puede bajar. En una parada que el autobús pasa de largo, el punto de aviso debe depender del progreso por la ruta, sin esperar a que el vehículo se detenga. Ajustaría el margen en trayectos reales para dar tiempo a solicitar la parada. La casilla de transbordos se aplicaría también al cambiar de bus a tren, entre buses o a Metro. El aviso final seguiría activo.

## Tiempo real y orden de desarrollo

- **Primero, EMT con catálogo estático y GPS.** Probaría una línea urbana con ida y vuelta diferentes, una que comparta varias paradas con otra línea y un itinerario mixto con Metro. El aviso funcionaría sin conexión después de instalar el catálogo.
- **Después, interurbanos y transbordos mixtos.** Verificaría dársenas, ramales, servicios cortos, horarios nocturnos y rutas entre municipios. Mediría tiempo de importación, tamaño del catálogo y latencia del buscador antes de empaquetar toda la red.
- **Por último, tiempo real por operador.** EMT documenta incidencias y tiempos de llegada. Su recurso de llegadas requiere una clave de MobilityLabs. Antes de usarlo para ajustar una alarma comprobaría si permite asociar una observación reciente al vehículo y recorrido elegidos. Sin esa relación, las llegadas servirían como contexto. Para interurbanos revisaría con el CRTM y los operadores qué datos están disponibles de forma reutilizable. La primera entrega no dependería de ese acceso ni introduciría una clave privada en el APK. [Tiempo real de EMT Madrid](https://datos.emtmadrid.es/es/dataset/tiempo-real-para-autobuses-de-emt) · [Documentación de la API de EMT](https://datos.emtmadrid.es/m360-swagger/docs)

Antes de ampliar la cobertura, comprobaría con viajes reales que el teléfono avisa en la parada esperada con pantalla apagada, que espera cuando el usuario sigue en la marquesina y que, tras un transbordo silencioso, el aviso final se mantiene. También probaría desvíos, interrupciones de GPS y cambios de catálogo que afecten a una rutina guardada.
