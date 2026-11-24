"""Derive connected rail itineraries and exact Renfe trip IDs from the official GTFS archives."""
import collections
import csv
import datetime as dt
import io
import statistics
import zipfile

# Published interchanges whose Metro platform IDs and Renfe stop IDs differ.
# Metro-to-Metro groups otherwise come from the CRTM parent_station field.
INTERCHANGES = {
    '10000': ('Príncipe Pío', (127, 198, 238)),
    '17000': ('Chamartín', (189, 261)),
    '18000': ('Atocha', (16,)),
    '18002': ('Nuevos Ministerios', (120, 155, 193)),
    '18003': ('Méndez Álvaro', (111,)),
    '18004': ('Delicias', (44,)),
    '18005': ('Pirámides', (93,)),
    '18101': ('Sol', (12, 35, 48)),
    '35600': ('Aluche', (100,)),
    '35603': ('Cuatro Vientos', (203,)),
    '35606': ('Móstoles Central', (214,)),
    '35608': ('Laguna', (104,)),
    '35609': ('Embajadores', (46, 92)),
    '37001': ('Villaverde Alto', (273,)),
    '60109': ('El Casar', (228,)),
    '70005': ('Vallecas / Sierra de Guadalupe', (25,)),
    '70100': ('Vicálvaro / Puerta de Arganda', (182,)),
    '70108': ('Coslada Central', (288,)),
    '97100': ('Pitis', (154,)),
    '98305': ('Aeropuerto T4', (285,)),
}
METRO_CONNECTIONS = {'par_4_' + str(platform): rid for rid, (_, platforms) in INTERCHANGES.items() for platform in platforms}
METRO_NAMES = {
    'ALFONSO XIII': 'Alfonso XIII',
    'AVENIDA DE AMERICA': 'Avenida de América',
    'MONCLOA': 'Moncloa',
    'PLAZA DE ESPAÑA': 'Plaza de España',
    'OPERA': 'Ópera',
    'GREGORIO MARAÑON': 'Gregorio Marañón',
    'MOSTOLES CENTRAL': 'Móstoles Central',
    'HOSPITAL DE MOSTOLES': 'Hospital de Móstoles',
    'GRAN VIA': 'Gran Vía',
}


def display_name(text):
    return METRO_NAMES.get(text, ' '.join(w.lower() if w.upper() in ('DE', 'DEL', 'LA', 'LAS', 'LOS', 'EL', 'Y') and i else w.title()
                                         for i, w in enumerate(text.split())))


def station_details(stop_id, stops, metro):
    stop = stops[stop_id]
    name = display_name(stop['stop_name']) if metro else stop['stop_name']
    rail = METRO_CONNECTIONS.get(stop_id) if metro else stop_id
    if rail in INTERCHANGES:
        place_id = 'rail-' + rail
        place_name = INTERCHANGES[rail][0]
    elif metro:
        parent = stop.get('parent_station') or stop_id.replace('par_', 'est_', 1)
        place_id = 'metro-' + parent
        parent_name = stops.get(parent, stop)['stop_name'].upper()
        place_name = display_name(parent_name.removeprefix('INTERCAMBIADOR DE '))
    else:
        place_id, place_name = 'rail-' + stop_id, name
    if stop_id in ('par_4_187', '17001'):
        place_name = 'Fuencarral (' + ('Metro' if metro else 'Cercanías') + ')'
    return {'id': stop_id, 'name': name, 'lat': float(stop['stop_lat']), 'lon': float(stop['stop_lon']),
            'placeId': place_id, 'placeName': place_name}


def add_places(line, stops, metro):
    line['stations'] = [station_details(s['id'], stops, metro) for s in line['stations']]
    if metro:
        line['attribution'] = 'Powered by CRTM · Datos tratados · https://www.crtm.es/'


def rows(zip_file, name):
    if name not in zip_file.namelist():
        return
    with zip_file.open(name) as stream:
        for row in csv.DictReader(io.TextIOWrapper(stream, encoding='utf-8-sig')):
            yield {key.strip(): value.strip() for key, value in row.items()}


def service_dates(archive):
    services = collections.defaultdict(set)
    ends = []
    for row in rows(archive, 'calendar.txt'):
        start = dt.datetime.strptime(row['start_date'], '%Y%m%d').date()
        end = dt.datetime.strptime(row['end_date'], '%Y%m%d').date()
        if (end - start).days > 400:
            raise ValueError('Calendario demasiado largo')
        ends.append(row['end_date'])
        while start <= end:
            if row[('monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday')[start.weekday()]] == '1':
                services[row['service_id']].add(start.strftime('%Y%m%d'))
            start += dt.timedelta(days=1)
    for row in rows(archive, 'calendar_dates.txt'):
        if row['exception_type'] == '1':
            services[row['service_id']].add(row['date'])
            ends.append(row['date'])
        elif row['exception_type'] == '2':
            services[row['service_id']].discard(row['date'])
    return services, max(ends, default='')


def itinerary_edges(stops):
    return {tuple(sorted((a, b))) for a, b in zip(stops, stops[1:])}


def contains(main, partial):
    indexes = {stop: index for index, stop in enumerate(main)}
    order = [indexes.get(stop, -1) for stop in partial]
    if -1 in order:
        return False
    return all(a < b for a, b in zip(order, order[1:])) or all(a > b for a, b in zip(order, order[1:]))


def main_patterns(trips, metro):
    patterns = collections.Counter(min(tuple(t['stopIds']), tuple(reversed(t['stopIds']))) for t in trips)
    used = set()
    paths = []
    # Prefer complete itineraries, treating shortened or express services on the same line as variants.
    for pattern, count in sorted(patterns.items(), key=lambda item: (-len(item[0]), -item[1], item[0])):
        circular = pattern[0] == pattern[-1]
        distinct = pattern[:-1] if circular else pattern
        if len(distinct) < 2 or len(set(distinct)) != len(distinct):
            continue
        if paths and (not metro and count < 10 or any(contains(path, distinct) for path, _ in paths)):
            continue
        edges = itinerary_edges(pattern)
        if not edges - used:
            continue
        paths.append((distinct, circular))
        used.update(edges)
    return paths


def extract_network(payload, metro, primary, url, parse_seconds):
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        stops = {r['stop_id']: r for r in rows(archive, 'stops.txt')}
        add_places(primary, stops, metro)
        route_type = '1' if metro else '2'
        excluded = '3' if metro else 'C5'
        line_by_route = {r['route_id']: r['route_short_name'] for r in rows(archive, 'routes.txt')
                         if r['route_type'] == route_type and r['route_short_name'] != excluded
                         and (metro or r['route_id'].startswith('10'))}
        agencies = sorted(a['agency_id'] for a in rows(archive, 'agency.txt') if a.get('agency_id'))
        trips = {t['trip_id']: t for t in rows(archive, 'trips.txt') if t['route_id'] in line_by_route}
        frequencies = {r['trip_id'] for r in rows(archive, 'frequencies.txt')}
        times = collections.defaultdict(list)
        for r in rows(archive, 'stop_times.txt'):
            if r['trip_id'] in trips and r['stop_id'] in stops and r['departure_time']:
                times[r['trip_id']].append((int(r['stop_sequence']), r['stop_id'], parse_seconds(r['departure_time'])))
        services, calendar_end = service_dates(archive)
        updated = max(i.date_time[:3] for i in archive.infolist())
        primary['sourceUpdated'] = dt.date(*updated).isoformat()

    by_name = collections.defaultdict(list)
    for trip_id, trip in trips.items():
        ordered = sorted(times[trip_id])
        if len(ordered) < 2 or any(a[0] >= b[0] or a[2] >= b[2] for a, b in zip(ordered, ordered[1:])):
            continue
        dates = sorted(services[trip['service_id']])
        if not dates:
            continue
        sequences, stop_ids, seconds = zip(*ordered)
        by_name[line_by_route[trip['route_id']]].append({
            'id': trip_id, 'routeId': trip['route_id'], 'dates': dates,
            'stopIds': list(stop_ids), 'times': seconds, 'sequences': sequences, 'frequencyBased': trip_id in frequencies,
        })

    lines = []
    schedules = {}
    for name, trips_in_line in sorted(by_name.items()):
        samples = collections.defaultdict(list)
        for trip in trips_in_line:
            for a, b, start, end in zip(trip['stopIds'], trip['stopIds'][1:], trip['times'], trip['times'][1:]):
                if 30 <= end - start <= 1800:
                    samples[tuple(sorted((a, b)))].append(end - start)

        def edge_time(a, b):
            values = samples.get(tuple(sorted((a, b))))
            return int(statistics.median(values)) if values else 120

        patterns = main_patterns(trips_in_line, metro)
        for index, (pattern, circular) in enumerate(patterns):
            route_code = ('L' + name if name.isdigit() else name) if metro else name
            line_id = ('METRO_' if metro else 'RENFE_') + route_code
            if len(patterns) > 1:
                line_id += '_' + str(index + 1)
            matching = [t for t in trips_in_line if contains(pattern, t['stopIds'])] if not metro else []
            if matching:
                schedules[line_id] = matching
            lines.append({
                'id': line_id, 'name': ('Metro · ' if metro else 'Cercanías · ') + route_code,
                'source': url, 'sourceUpdated': dt.date(*updated).isoformat(), 'calendarEnd': calendar_end,
                'attribution': 'Powered by CRTM · Datos tratados · https://www.crtm.es/' if metro else 'Origen de los datos: Renfe Operadora · CC BY 4.0',
                'fetchedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                'notice': 'Topología y duraciones estimadas del GTFS de CRTM; consulta el estado del servicio.' if metro else '',
                'routeIds': sorted(rid for rid, group in line_by_route.items() if group == name),
                'agencyIds': agencies, 'stations': [station_details(sid, stops, metro) for sid in pattern],
                'segmentSeconds': [edge_time(a, b) for a, b in zip(pattern, pattern[1:])],
                'closingSegmentSeconds': edge_time(pattern[-1], pattern[0]) if circular else 0,
                'trips': [],
            })
    return lines, schedules
