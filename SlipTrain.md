Problema: 
Mucha gente se queda dormida en el tren y se pasa de estacion. Molestia en dar la vuelta y perdida de mucho tiempo.

IDea:
Desarrollar una aplicacion que te avise mediante alarmas o algun medio (el usuario puede elegir si suena por los auriculares, altavoz o si detecta los auriculares se seleccione de forma automatica, configurable), para que despierte a la persona que se quedó dormida una parada antes de la deseada (configurable por el usuario). 

Implementacion:
Usar GPS cuando el dispositivo tenga esa señal y asi saber en que parada está el usuario. Si no se detecta la señal GPS, ya que varios trenes pasan todo su recorrido o gran parte bajo tunel, usar alguna tecnologia de localizacion como A-GPS, UWB, BLE, navegacion inercial cuantica/acelerómetros, hacer un calculo mediante la distacia de estacion-estacion, velocidad media del tren o usar algun sistema propio de la ferroviaria para saber la posicion del tren.

Para no depender del GPS en tuneles, usar los GTFS-RT, en el caso de Renfe: Ubicacion de los vehiculos, Incidencias y avisos, Horarios de viaje; en los demas transportes: GTFS Red de Metro, Red de EMT, Red de Metro ligero, Red de Autobuses interurbanos, que se usan para saber las posicion de dinamica en tiempo real de los trenes e informaciones de retrasos y horarios. Cargar en memoria o base de datos relacional y cruzar los IDs con los bin de los GTFS-RT que llegan. (Preferiria no usar un servidor, todo en la aplicacion mediante java Android studio)
https://data.renfe.com/dataset?tags=Tiempo+Real&res_format=GTFS-RT
https://datos.crtm.es/datasets/5c7f2951962540d69ffe8f640d94c246/about

Por ahora estaria bien probar con la red de metro y cercanías para los transbordo. Para un prueba en la vida real, incorpora la linea 3 de metro y la C5 de renfe. 
Hacer la interfaz amigable para elegir entre metro, renfe y opcion de transbordo.

Un patrón de arquitectura en capas, Mvc o lo que sea pertinente (ya que no quiero usar una base de datos)


Modificación de lógica o funcionamiento.
	Eliminar selección de metro, cercanías y transbordo: Será todo unificado, simplemente el usuario tendrá que poner el destino y con la ubicación de ese momento se calcule o escoja la ruta. Parece confuso, pero con las señales gps que recoje el dispositivo entre paradas, que la aplicación se autoritaria. Por ejemplo: yo es estoy en Alfonso XII de la linea 4 de metro y quiero ir hasta Mostoles central (la cual tiene renfe y metro), solo pondria el destino y con la señal gps con la que se corrige la app sepa cual es la parada anterior. Podriamos usar los archivos GTFS para cojer cual es la mejor ruta de destino y que con el gps corrija cambios.
	Con esto quitamos cosas que un usuario le incomoda y simplemente quiere que se le avise antes de despertarse. 


Automatización de alarmas.
	Este seria un apartado nuevo. Que el usuario pueda automatizar sus idas y venidas. Por ejemplo: El usuario coge el tren  de Mostoles central a Alfonso XII a las 7:00 y vuelve de Alfonso XII a Mostoles central a las 14:00. Pues que con la hora que ponga el usuario  automaticamente la alarma y el usuario ya no tendra que siempre darle al boton de iniciar viaje ni nada de eso. Cuando se vea que el dispositivo se está moviendo a velocidad inhumana o que el dispositivo detecte estación, estacion y estacion, se supone que el viajero comenzó su viaje.	

Apartado de sonido y vibracion.
	Quita el apartado de "salida de sonido", ya se sabe que va a ser automático por lo que no hace falta que ocupe espacio.
	A cambio de eso agrega un selector de archivos de audio para que el usuario elija la alarma, notificación o música que quiera.


Diseño
	Sabiendo las cosas de antes quiero que cambies el diseño y que todo se vea como al de WhatsApp Image 2026-11-20 at 21.57.06.jpeg la parte de "Alarm in 8 hours 32 minutes" puedes poner algo relevante. En cada apartado que se pueda apagar o encender la alarma y tal.
	En la imagen	WhatsApp Image 2026-11-20 at 21.56.27.jpeg es como quiero que sea vea la configuración de la alarma: la parte de selección de horas puedes hacerlo mas pequeña para que se pueda poner la hora de ida y la hora que vuelve el usuario del destino, la elección de audio, elección de que días quieres que funcione (al darle clics quiero que aparezca opciones predeterminadas como Lunes-Miércoles-Viernes, Sábado-Domingo, y Lunes a Viernes), y una etiqueta si quiere (si no pone nada que simplemente se ponga el destino que haya elegido el usuario).



Wireframe diseño (Deberia haberlo hecho al principio)

El wireframe esta muy bien hecho y quiero que sigas esos mismo diseños pero quiero que cambies algunas cosas, seguiré el nombre que tu les pusiste en el wireframe.

Viaje puntual - Inicio - Proximo viaje
	No eliminaria directamente esto pero quiero que solo se muestre cuando exista una rutina guardada.
	
Viaje puntual - Buscador unificado
	Me gusta como muestra las paradas que contengan el nombre de la que busque, para diferenciarlos mejor, a cambio de los iconos de los trenes, pondria los iconos de renfe y metro,
	/home/yori/Documentos/ChatGPT/sleep_Train/renfelogo.png
	/home/yori/Documentos/ChatGPT/sleep_Train/MetroMadridLogo.svg.webp
	Cuando el usuario seleccione una estacion, quiero que se le muestre las distintas rutas que puede usar para llegar al destino, como lo que usa Maps by Google, te paso una imagen para que tengas referencia /home/yori/Documentos/ChatGPT/sleep_Train/Pasted image 20261123184048.png (lo de seleccion de rutas tambien quiero que esté cuando se cree una alarma recurrente). Ya teniendo una ruta fija, la aplicacion siempre sabrá donde hace cambios y no los dé por sentado.
	
Viaje puntual - Inicio - Buscando ubicacion
	Por cierto, si no se encuentra una ubicacion precisa al iniciar el viaje, no quiero que se de a reintentar, simplemente que cuando ya se encuentra señal gps en posterior que siga con esa.	Recuerda que el usuario estará cansado de sus dias y solo quiere poner su alarma y no pasarse de estación, si el usuario tiene que darle varias veces a reintentar, fallaría la idea que tengo de la app, asi que directamente podrias eliminar lo de "Sin ubicacion precisa"

Seguimiento y avisos
	Quiero que cuando se inicie el viaje se muestre como un mapa o linea de progresion con puntos (cada punto es una estacion) y que mientras vas avanzando te muestre en que parada vas (algo como lo que ya haces), algo como en la imagen WhatsApp Image 2026-11-23 at 18.05.45.jpeg y lo que tienes hecho en Seguimiento y avisos pero mas minimaslista, sin tantos datos, en cada punto se muestre el nombre de la parada en el momento, de la siguiente y la que le sigue (esta ultima parada como mucho, si el nombre es muy largo no lo pongas o ponlo pero con puntos suspensivos, no quiero que este como tal muy lleno), al final de la linea el punto que muestre el numero de estaciones restantes y cuando ya no queden muchas que muestre el nombre de la ultima parada, sigue la paleta de colores de nuestra app y qe la linea que sea de forma horizontal (igualmente pon un diseño en el wireframe que sea la linea en vertical para ver si me gusta una u otra.)
	Por cierto quiero que siga apareciendo el apartado de alarmas recurrentes
	
Crear una alarma - Audio de alarma
	Al darle a "Elegir archivo" no quiero que me abra otra pantalla de la misma aplicacion, directamente que abra el selector de archivos del sistema.
	Al darle a "Escuchar una prueba" que tampoco abra otra pantalla, con darle click que se escuche la prueba y al dar click de nuevo que se apague


FIltro en las rutas


Titulo By Wvalen

IDea By Yori (me quedo dormido muchas veces)