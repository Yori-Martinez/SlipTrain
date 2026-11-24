# SlipTrain 0.5

SlipTrain es una app Android nativa que avisa antes de llegar al destino de un viaje en tren. Combina Metro y Cercanías de Madrid en un solo buscador. Calcula las rutas y guarda las alarmas localmente, sin cuentas, servidor propio ni base de datos.

La idea y los créditos están en [SlipTrain.md](SlipTrain.md). La app admite Android 8.0 o posterior. Al compilar el código actual, el APK de depuración queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Viaje puntual

1. En **Viaje ahora**, busca y selecciona el destino. Pulsa **Iniciar viaje** y SlipTrain calcula el itinerario al detectar la estación de salida. Android pide ubicación precisa, notificaciones y alarmas exactas cuando corresponde. Puedes apagar la pantalla mientras el aviso espera una ubicación válida.
2. Si quieres revisar el itinerario antes de iniciar, abre **Elegir ruta**. **Ordenar** permite usar el tiempo de viaje o los transbordos. **Filtrar** muestra todas las rutas, solo Metro o solo Cercanías. **Más rutas** amplía las recomendaciones y muestra los enlaces sin salida prevista que indique Cercanías.
3. Deja marcada **Avisarme en los transbordos** para avisar antes de cambiar de línea según **Ajustes → Antelación del aviso**. Al pulsar **Estoy despierto** antes del cambio, el seguimiento continúa hasta el intercambiador. Si desmarcas la casilla, SlipTrain pasa a detectar el siguiente tramo al acercarse al intercambiador. **Ya estoy en el siguiente tren** permite confirmar el embarque.
4. En el último tramo se avisa una parada antes del destino por defecto. Puedes elegir la antelación y cambiar entre la línea de progreso horizontal y vertical en **Ajustes**.

Las rutas se comparan por duración estimada y transbordos. En los viajes puntuales, los horarios de Cercanías añaden la espera y permiten comprobar si da tiempo a enlazar. Si hay datos GTFS-RT recientes y compatibles, también cuentan los retrasos, las cancelaciones, las paradas suprimidas y los cortes de servicio. Para Metro se usan las duraciones del catálogo. La ruta seleccionada se conserva al actualizar las estimaciones.

## Alarmas recurrentes

En **+ Nueva → Desde y hasta**, elige la salida y el destino. Al completar el trayecto se abren las alternativas. Las pestañas **Ida** y **Vuelta** permiten elegir cada sentido. Desde **Cambiar rutas** puedes volver a hacerlo con las mismas estaciones. Las duraciones de la rutina proceden del catálogo. Indica también las horas, los días de repetición y si quieres que suenen los avisos de transbordo. La etiqueta es opcional. Cuando está vacía se muestra el nombre del destino. Cada tarjeta permite activar, desactivar y editar la alarma. **Próximo viaje** aparece al guardar una rutina.

La hora indica el inicio de una ventana de detección de hasta 30 minutos. Al detectar movimiento compatible con el trayecto, SlipTrain inicia el seguimiento y arma el aviso de llegada sin tocar un botón. La detección con la pantalla apagada necesita el permiso de ubicación en segundo plano. Se basa en GPS reciente y avance por la ruta, corroborado con velocidad o estaciones sucesivas. Si en la ventana faltan observaciones suficientes, ese viaje queda sin iniciar.

## GPS, transbordos y audio

Durante el viaje, la posición reciente corrige el avance y la hora del aviso. Si varias lecturas GPS precisas muestran un desplazamiento compatible con una única alternativa, SlipTrain recalcula el resto del recorrido y mantiene el destino y los ajustes del aviso. Una ruta elegida o guardada en una rutina sirve como itinerario inicial. Durante los periodos sin señal o con recorridos ambiguos, se mantiene la estimación del último itinerario confirmado. Cuando Renfe publica datos recientes del tren identificado, SlipTrain utiliza esa información según la política descrita en [AUTOMATIC_TRACKING.md](docs/AUTOMATIC_TRACKING.md).

Desde el editor o **Ajustes → Audio y vibración**, abre **Audio de la alarma**. **Elegir archivo** abre el selector de Android. **Escuchar una prueba** reproduce el tono hasta cinco segundos y la misma fila permite detenerlo. La copia se guarda en el almacenamiento privado del móvil. Puedes activar o silenciar el sonido por altavoz y por auriculares de forma independiente. **Vibración** tiene su propio ajuste.

## Datos y alcance

La red empaquetada incluye Metro y Cercanías de Madrid. El GTFS disponible de Metro aporta topología y tiempos históricos o estimados. Cercanías incluye horarios fechados y usa GTFS en tiempo real cuando puede asociar un tren. La app conserva el aviso estimado si falla el acceso al operador.

Android puede detener el seguimiento si fuerzas la detención o retiras los permisos. El reinicio reprograma las rutinas habilitadas y descarta un trayecto que estuviera en curso. La detección de viaje en túneles y el audio por Bluetooth necesitan una prueba física.

## Compilar y probar

JDK 17, SDK 36 y Build Tools 36.0.0. Se conserva la configuración actual del proyecto: **AGP 9.4.1 y Gradle Wrapper 9.6.0**. Abre esta carpeta en Android Studio o configura `JAVA_HOME` y `ANDROID_HOME`.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

Diagnóstico opcional con conexión a los feeds actuales:

```bash
./gradlew :app:probeFeeds
```

Actualización del catálogo empaquetado, con Python estándar:

```bash
python3 scripts/update_catalog.py
```

Admite `--renfe-input archivo.zip`, `--metro-input archivo.zip` y `--output archivo.json`. El script regenera el catálogo y las tablas de viajes de Cercanías a partir de los dos archivos oficiales. El APK incluye estos datos.

## Fuentes y privacidad

- [Renfe: GTFS estático](https://data.renfe.com/dataset/horarios-cercanias) y [tres feeds GTFS-RT](https://data.renfe.com/dataset?res_format=GTFS-RT&tags=Tiempo+Real). Origen de los datos: Renfe Operadora, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Datos filtrados y transformados; Renfe no respalda esta app.
- [GTFS Metro del CRTM](https://datos.crtm.es/datasets/5c7f2951962540d69ffe8f640d94c246/about), [licencia del CRTM](https://www.crtm.es/licencia-de-uso). Datos transformados para el planificador. [Powered by CRTM](https://www.crtm.es/).
- [Teleindicadores de Embajadores](https://serviciosapp.metromadrid.es/servicios/rest/teleindicadores/0304) y [Villaverde Alto](https://serviciosapp.metromadrid.es/servicios/rest/teleindicadores/0357). Servicio del dominio del operador sin contrato público de API localizado; su disponibilidad no está garantizada.

No hay cuentas, publicidad ni analítica. Las consultas directas a los operadores revelan la IP del dispositivo; no envían las coordenadas del pasajero. La sesión y los ajustes se guardan en archivos privados; las copias de seguridad de esos datos están deshabilitadas.

Pruebas realizadas y validación física pendiente: [TESTING.md](docs/TESTING.md).
