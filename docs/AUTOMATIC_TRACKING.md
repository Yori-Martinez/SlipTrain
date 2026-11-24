# Seguimiento automático de SleepTrain

Cambios de la versión 0.4 del 18 de noviembre de 2026. Se retiran de la pantalla los selectores del tren del primer tramo y del transbordo, el interruptor de corrección GPS y los modos manuales de salida de sonido. El recorrido descargado, el GPS, la consulta de Renfe y las incidencias siguen funcionando.

## Interfaz y ajustes implícitos

- Metro usa el recorrido descargado y la ubicación sin presentar una lista con una sola opción.
- Renfe intenta enlazar automáticamente el viaje real; no pide elegir un ID. También lo hace al subir al siguiente tren de un transbordo.
- La corrección de ubicación se intenta durante todo tramo activo. Las preferencias antiguas que desactivaban el GPS no la deshabilitan. Android sigue teniendo el control de los permisos y del servicio de ubicación; si no permite obtener posiciones, se informa y se conserva la estimación de respaldo.
- «Salida de sonido» muestra únicamente **Automático**. Se usan los auriculares conectados y, en su ausencia o desconexión, el altavoz. Se conserva la comprobación de salida antes de hacer audible el tono y la vibración de respaldo. Las preferencias antiguas de auriculares/altavoz no alteran esta política. La vibración adicional y la prueba de sonido siguen disponibles.
- La salida inicial y el embarque después de un transbordo siguen requiriendo confirmación. Identificar un tren no confirma por sí mismo que se haya subido a él.

## Cómo se enlaza Renfe

`AutoTrainMatcher` trabaja localmente sobre el catálogo y los vehículos GTFS-RT. No envía coordenadas a Renfe ni añade servidores ni bases de datos.

1. Exige un `trip_id` exacto del catálogo C5 Madrid y comprueba ruta, servicio/calendario, sentido y cobertura de todas las estaciones del tramo. Descarta trenes parciales que no cubren el destino y plantillas por frecuencias.
2. Compara las coordenadas del móvil con las del tren, o con una estación explícitamente `STOPPED_AT` cuando faltan coordenadas. Resuelve `stop_sequence` mediante el viaje estático. `IN_TRANSIT_TO` sin coordenadas no demuestra presencia en la estación.
3. Para identificar exige GPS de hasta 15 s de antigüedad, vehículos de hasta 30 s y una diferencia de hasta 20 s entre las mediciones. El radio de coincidencia depende de precisión y diferencia temporal, limitado a 350 m.
4. Exige dos observaciones inequívocas del mismo viaje/fecha, separadas al menos 10 s, con movimiento de al menos 150 m y progreso de 0,15 estaciones en el sentido previsto. La marca temporal del vehículo debe avanzar. Repetir la misma cabecera, una ubicación almacenada o permanecer quieto no confirma el ID.
5. Si coinciden varios trenes, no elige uno arbitrariamente. Sigue por GPS y datos locales y vuelve a intentarlo. No elige solo por la hora programada. Por ello la asociación puede tardar varias estaciones o no completarse si los datos son insuficientes.
6. Una contradicción clara del GPS libera la asociación y elimina las previsiones anteriores. Se busca otra con nuevas observaciones. Si la señal desaparece después de haber asociado un tren, se conserva ese enlace para consultar las previsiones del operador en el túnel; no se inventa un ID nuevo sin GPS.

Esto es una inferencia por observaciones compatibles, no una prueba de identidad del pasajero ni una garantía de reconocer siempre el tren. Trenes próximos, cobertura insuficiente, feeds incompletos o IDs no presentes en el catálogo impiden distinguirlo.

## Incidencias y prioridad del GPS

Las incidencias se siguen consultando y quedan visibles. Si hay posición GPS válida y reciente, una cancelación, supresión de parada o corte comunicado por el operador no adelanta por sí solo la alarma: el aviso sigue la posición y su plazo corregido. Llegar a la estación de aviso siempre dispara. Si ya no hay GPS reciente, se conserva el aviso inmediato de las incidencias críticas pertinentes y el respaldo local. Los retrasos y previsiones no sustituyen a una ubicación reciente del móvil.

## Comprobación

Las pruebas nuevas incluyen coincidencia con coordenadas y con estaciones, secuencias, trenes ambiguos, posiciones repetidas, sentido contrario, servicios parciales, fecha incorrecta, datos caducados, ID desconocido, pérdida de señal y liberación por contradicción. También se comprueba el cruce del ID asociado con posiciones, previsiones e incidencias, la política de GPS ante incidencias y la migración de ajustes antiguos.

La semántica de campos se ha contrastado con la [referencia oficial GTFS Realtime](https://gtfs.org/documentation/realtime/reference/). No se ha probado todavía la asociación automática de un pasajero durante un viaje físico.


## Datos del planificador de la versión 0.5

El catálogo se generó el 20 de noviembre de 2026 con 316 destinos y 30 itinerarios de Metro y Cercanías. Agrupa andenes del mismo intercambiador por `parent_station` del CRTM y por correspondencias verificadas. Las duraciones se estiman a partir de los tiempos entre paradas en GTFS. El cálculo añade cinco minutos por cambio de tren y usa el GPS durante el viaje. Los tiempos de espera y los cortes de servicio no forman parte de la estimación de la ruta.

- **Powered by CRTM.** Datos tratados de la red de Metro. El archivo GTFS oficial incluye 120 viajes de referencia por frecuencias, 13 líneas comerciales y `feed_version` del 27 de mayo de 2025. El calendario vence el 27 de mayo de 2026. La app usa sus conexiones y tiempos orientativos junto con la extensión de L3 hasta El Casar que ya recoge el proyecto. [CRTM](https://www.crtm.es/), [descarga oficial GTFS](https://crtm.maps.arcgis.com/sharing/rest/content/items/5c7f2951962540d69ffe8f640d94c246/data), [licencia de uso](https://www.crtm.es/licencia-de-uso).
- **Origen de los datos: Renfe Operadora.** El GTFS descargado cubre del 20 de noviembre al 19 de diciembre de 2026. El catálogo conserva 35.724 identificadores de viajes en los itinerarios de Cercanías incluidos para enlazarlos con posiciones en tiempo real. Los recorridos quedan disponibles al vencer el calendario, con corrección por GPS. [Descarga oficial GTFS](https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip), [licencia CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

El ejemplo puntuado con estas duraciones va de Alfonso XIII por L4 hasta Alonso Martínez, sigue por L10 hasta Cuatro Vientos y termina en Móstoles Central por C5. Con una parada de antelación, el aviso final corresponde a Las Retamas. El calculador da 55 minutos aproximados. El tramo de L10 utilizado queda al sur del corte Cuzco–Nuevos Ministerios publicado el 20 de noviembre. Los cambios de frecuencia de C5 del fin de semana pueden añadir espera. [Alfonso XIII](https://www.crtm.es/tu-transporte-publico/metro/estaciones/4_66/), [Móstoles Central](https://www.crtm.es/tu-transporte-publico/metro/estaciones/4_214/), [avisos CRTM](https://www.crtm.es/informacion-actualizada), [aviso Renfe de noviembre](https://grupo.renfe.com/es/es/sala-de-prensa/noticias/2026/11/la-linea-c5-de-cercanias-madrid-modifica-el-servicio-primeras-horas-fines-de-semana-noviembre).
