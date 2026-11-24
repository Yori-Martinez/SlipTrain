#!/usr/bin/env python3
"""Build the local Madrid rail network and Renfe trip indexes from official GTFS."""
import argparse
import csv
import datetime as dt
import io
import json
import statistics
import urllib.request
import zipfile
from pathlib import Path

RENFE_URL = 'https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip'
METRO_URL = 'https://crtm.maps.arcgis.com/sharing/rest/content/items/5c7f2951962540d69ffe8f640d94c246/data'
C5 = ['35607', '35606', '35610', '35605', '35604', '35603', '35602', '35601', '35600', '35608', '35609', '18000', '18003', '35702', '35703', '35704', '37001', '35009', '35001', '35011', '35010', '35002', '35012']
L3 = ['par_4_' + str(i) for i in [53, 52, 51, 50, 49, 48, 47, 46, 45, 44, 43, 267, 268, 269, 270, 271, 272, 273, 228]]


def seconds(value):
    h, m, s = map(int, value.split(':'))
    if h < 0 or not 0 <= m < 60 or not 0 <= s < 60: raise ValueError('Hora GTFS inválida')
    return h * 3600 + m * 60 + s


def extract(payload, metro=False):
    archive = zipfile.ZipFile(io.BytesIO(payload))
    def rows(name):
        if name not in archive.namelist(): return []
        with archive.open(name) as file:
            return [{k.strip(): v.strip() for k, v in row.items()} for row in csv.DictReader(io.TextIOWrapper(file, encoding='utf-8-sig'))]
    ids = L3 if metro else C5
    stops = {s['stop_id']: s for s in rows('stops.txt') if s['stop_id'] in ids}
    if len(stops) != len(ids): raise ValueError('Faltan estaciones del recorrido de referencia')
    routes = [r for r in rows('routes.txt') if (r['route_short_name'] in ('3', 'L3') if metro else r['route_short_name'] == 'C5' and r['route_id'].startswith('10'))]
    route_ids = {r['route_id'] for r in routes}
    if not route_ids: raise ValueError('No se encuentra la línea solicitada')
    trips = {t['trip_id']: t for t in rows('trips.txt') if t['route_id'] in route_ids}
    stop_times = {tid: [] for tid in trips}
    for row in rows('stop_times.txt'):
        if row['trip_id'] in trips and row['stop_id'] in stops: stop_times[row['trip_id']].append(row)
    weekdays = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday']
    services, ends = {}, []
    for row in rows('calendar.txt'):
        start = dt.datetime.strptime(row['start_date'], '%Y%m%d').date(); end = dt.datetime.strptime(row['end_date'], '%Y%m%d').date()
        if (end - start).days > 400: raise ValueError('Calendario demasiado largo')
        dates = set(); ends.append(row['end_date'])
        while start <= end:
            if row[weekdays[start.weekday()]] == '1': dates.add(start.strftime('%Y%m%d'))
            start += dt.timedelta(days=1)
        services[row['service_id']] = dates
    for row in rows('calendar_dates.txt'):
        dates = services.setdefault(row['service_id'], set())
        if row['exception_type'] == '1': dates.add(row['date']); ends.append(row['date'])
        elif row['exception_type'] == '2': dates.discard(row['date'])
    frequencies = {f['trip_id'] for f in rows('frequencies.txt')}
    samples = [[] for _ in range(len(ids) - 1)]; output = []
    for tid, trip in trips.items():
        ordered = sorted(stop_times[tid], key=lambda r: int(r['stop_sequence']))
        if len(ordered) < 2: continue
        positions = [ids.index(r['stop_id']) for r in ordered]; direction = 1 if positions[-1] > positions[0] else -1
        times = [seconds(r['departure_time']) for r in ordered]
        if any((b - a) * direction <= 0 for a, b in zip(positions, positions[1:])) or any(b <= a for a, b in zip(times, times[1:])): continue
        for i in range(len(ordered) - 1):
            delta = times[i + 1] - times[i]
            if abs(positions[i + 1] - positions[i]) == 1 and 30 <= delta <= 1800: samples[min(positions[i], positions[i + 1])].append(delta)
        dates = sorted(services.get(trip['service_id'], set()))
        if dates:
            output.append({'id': tid, 'routeId': trip['route_id'], 'dates': dates, 'stopIds': [r['stop_id'] for r in ordered], 'times': times, 'sequences': [int(r['stop_sequence']) for r in ordered], 'frequencyBased': tid in frequencies})
    if not metro and (not output or any(not s for s in samples)): raise ValueError('C5 incompleta')
    provisional = [120] * (len(ids) - 2) + [180]
    durations = [int(statistics.median(s)) if s else provisional[i] for i, s in enumerate(samples)]
    notice = '' if not metro else ('Sin GTFS-RT público verificado. ' + ('El GTFS descargado no contiene viajes L3. ' if not output else '') + 'Tramos sin horario: estimación provisional. El Casar usa la referencia geográfica del intercambiador, no un andén L3 verificado.')
    return {'id': 'METRO_L3' if metro else 'RENFE_C5', 'name': 'Metro · L3' if metro else 'Cercanías · C5', 'source': METRO_URL if metro else RENFE_URL,
            'attribution': 'CRTM · licencia de uso CRTM' if metro else 'Origen de los datos: Renfe Operadora · CC BY 4.0',
            'fetchedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'calendarEnd': max(ends, default=''), 'notice': notice, 'routeIds': sorted(route_ids),
            'agencyIds': sorted({r.get('agency_id', '') for r in routes if r.get('agency_id')} or {a.get('agency_id', '') for a in rows('agency.txt') if a.get('agency_id')}),
            'stations': [{'id': sid, 'name': stops[sid]['stop_name'].title() if metro else stops[sid]['stop_name'], 'lat': float(stops[sid]['stop_lat']), 'lon': float(stops[sid]['stop_lon'])} for sid in ids], 'segmentSeconds': durations, 'trips': output}


def download(url):
    with urllib.request.urlopen(url, timeout=60) as response: payload = response.read(32 * 1024 * 1024 + 1)
    if len(payload) > 32 * 1024 * 1024: raise ValueError('GTFS demasiado grande')
    return payload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--renfe-input', type=Path); parser.add_argument('--metro-input', type=Path)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'app/src/main/assets/transit_catalog.json')
    args = parser.parse_args()
    from rail_network import extract_network
    renfe_payload = args.renfe_input.read_bytes() if args.renfe_input else download(RENFE_URL)
    metro_payload = args.metro_input.read_bytes() if args.metro_input else download(METRO_URL)
    renfe = extract(renfe_payload)
    metro = extract(metro_payload, metro=True)
    renfe_extensions, schedules = extract_network(renfe_payload, False, renfe, RENFE_URL, seconds)
    metro_extensions, _ = extract_network(metro_payload, True, metro, METRO_URL, seconds)
    lines = [renfe, metro, *metro_extensions, *renfe_extensions]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'schema': 2, 'lines': lines}, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    trips_dir = args.output.parent / 'regional_trips'
    trips_dir.mkdir(exist_ok=True)
    for line_id, trips in schedules.items():
        (trips_dir / f'{line_id}.json').write_text(json.dumps(trips, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    for line in lines:
        count = len(line['trips']) + len(schedules.get(line['id'], ()))
        print(f"{line['id']}: {len(line['stations'])} estaciones, {count} viajes; calendario hasta {line['calendarEnd']}")


if __name__ == '__main__': main()
