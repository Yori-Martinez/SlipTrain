# Corrección de posición — SleepTrain

> En 0.4 el GPS queda siempre habilitado durante el tramo y desaparece su interruptor. Renfe intenta asociar el tren automáticamente. Las incidencias críticas no adelantan el aviso por sí solas cuando existe GPS reciente. Estas reglas sustituyen las opciones y el tratamiento de incidencias descritos en la versión 0.3 de esta nota; véase [AUTOMATIC_TRACKING.md](AUTOMATIC_TRACKING.md).

Cambio del 18 de noviembre de 2026 a partir del problema observado por el usuario: el aviso sonó dos o tres estaciones antes. Sin un registro de aquel trayecto no se puede reconstruir la causa exacta; el código anterior adelantaba el respaldo un 25 % de todo el tiempo restante más un minuto, y el GPS solo aceptaba dos lecturas en estación y progreso hacia delante.

## Comportamiento

La ruta, estaciones y tiempos descargados de Renfe/CRTM siguen siendo la base. La corrección de ubicación está activada por defecto, también al actualizar desde 0.2. Desde 0.4 no hay un interruptor para desactivarla dentro de la app. La app solicita ubicación precisa durante el uso y mantiene un servicio de ubicación mientras dura el tramo; no pide acceso permanente.

- Se solicitan actualizaciones cada segundo durante el viaje, también si no hay señal en ese instante. Se escuchan GPS, red y el proveedor fusionado del sistema cuando existe. Son peticiones al sistema, no una garantía de obtener una lectura cada segundo.
- Una lectura de hasta 15 s de antigüedad, con precisión declarada de hasta 25 m y cerca inequívocamente de una estación, basta para corregir inmediatamente. No hace falta mantener señal durante varios segundos. Esto aprovecha recuperaciones breves, incluidas las que puedan producirse en estaciones. Abrir las puertas no garantiza señal GPS y la app no detecta la apertura.
- Con precisión de entre 25 y 80 m se requieren dos lecturas distintas, separadas al menos dos segundos y no más de quince. Se rechazan duplicados, lecturas antiguas/futuras, coordenadas inválidas, ubicaciones ambiguas y saltos físicamente inverosímiles entre lecturas aceptadas.
- Entre estaciones se puede proyectar una lectura precisa sobre un corredor estrecho entre los puntos descargados. Es una aproximación mediante segmentos rectos, no el trazado completo de la vía. Las curvas, túneles y puntos alejados del corredor pueden impedir la corrección; en ese caso se mantiene la estimación.
- El GPS puede corregir hacia delante o hacia atrás y actualizar el anclaje aunque el tren siga en la misma estación. Una lectura almacenada repetida no aplaza el aviso.
- Una posición válida del móvil tiene prioridad durante 30 s sobre posiciones y previsiones de Renfe. Si la señal indica que aún no se ha llegado a la estación de aviso, la cuenta atrás por sí sola no dispara durante esa ventana. Llegar a la estación de aviso o sobrepasarla sí dispara inmediatamente.
- Después, se aceptan datos recientes del operador que sean posteriores a la corrección. Las incidencias siguen visibles; desde 0.4 no adelantan por sí solas el aviso mientras haya GPS reciente.
- Sin señal ni previsión reciente, se calcula el tiempo restante desde el último punto corregido usando los tiempos descargados y un margen fijo de **15 s**. Ya no se descuenta un porcentaje de todo el viaje. Los intervalos medidos entre estaciones ajustan gradualmente el ritmo del tramo, con límites para no convertir una observación anómala en un aplazamiento ilimitado.
- Posición, tiempo monotónico de la lectura, calibración y plazos se guardan con la sesión. Tanto el servicio como el receptor de respaldo reconstruyen el mismo cálculo. Cada transbordo limpia esas observaciones y requiere confirmar la nueva salida.

## Pantalla

Durante el viaje se muestran el punto o tramo de la última observación, progreso, paradas hasta el aviso, precisión y antigüedad de la ubicación. La etiqueta distingue GPS reciente, previsión Renfe y estimación. La cuenta atrás es orientativa y puede cambiar al corregirse la posición. El formulario de preparación queda oculto durante el viaje y los ajustes de audio se despliegan cuando se necesitan.

## Límites que siguen vigentes

Sin GPS útil ni información reciente del tren, no se puede conocer con exactitud la posición: la estimación puede adelantarse o retrasarse. Los tiempos de L3 siguen siendo provisionales; sus teleindicadores no identifican el tren abordado y no mueven la posición. Esta versión necesita nuevas pruebas supervisadas en el teléfono y las líneas reales, con pantalla apagada y pérdidas de señal.

Referencias de implementación Android: [Location y tiempo monotónico](https://developer.android.com/reference/android/location/Location), [LocationManager](https://developer.android.com/reference/android/location/LocationManager), [permisos precisos y aproximados](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime). La precisión declarada por Android es una estimación estadística, no una garantía de posición exacta.
