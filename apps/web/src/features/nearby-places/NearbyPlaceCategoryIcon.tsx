import { createElement } from 'react';

import type { NearbyPlaceCategory } from './api/fetchNearbyPlaces';

type Shape = { tag: 'circle' | 'line' | 'path' | 'polyline' | 'rect'; attrs: Record<string, string> };

const SHAPES: Readonly<Record<NearbyPlaceCategory, readonly Shape[]>> = {
  CAFE: [
    { tag: 'path', attrs: { d: 'M5 7h10v5a5 5 0 0 1-5 5 5 5 0 0 1-5-5V7Z' } },
    { tag: 'path', attrs: { d: 'M15 9h1.5a2.5 2.5 0 0 1 0 5H15' } },
    { tag: 'line', attrs: { x1: '6', y1: '20', x2: '17', y2: '20' } },
  ],
  RESTAURANT: [
    { tag: 'path', attrs: { d: 'M7 3v7M4.5 3v4.5A2.5 2.5 0 0 0 7 10a2.5 2.5 0 0 0 2.5-2.5V3M7 10v11' } },
    { tag: 'path', attrs: { d: 'M16 3c-2 3-2 7 0 9v9M16 3c3 2 4 6 1 9h-1' } },
  ],
  CONVENIENCE_STORE: [
    { tag: 'path', attrs: { d: 'M4 9h16l-2-5H6L4 9Z' } },
    { tag: 'path', attrs: { d: 'M5 9v11h14V9M9 20v-6h6v6' } },
    { tag: 'line', attrs: { x1: '8', y1: '4', x2: '8', y2: '9' } },
    { tag: 'line', attrs: { x1: '12', y1: '4', x2: '12', y2: '9' } },
    { tag: 'line', attrs: { x1: '16', y1: '4', x2: '16', y2: '9' } },
  ],
  HOSPITAL: [
    { tag: 'rect', attrs: { x: '4', y: '4', width: '16', height: '16', rx: '2' } },
    { tag: 'line', attrs: { x1: '12', y1: '8', x2: '12', y2: '16' } },
    { tag: 'line', attrs: { x1: '8', y1: '12', x2: '16', y2: '12' } },
  ],
  PHARMACY: [
    { tag: 'path', attrs: { d: 'M8 5a4 4 0 0 1 5.7 0l5.3 5.3a4 4 0 0 1-5.7 5.7L8 10.7A4 4 0 0 1 8 5Z' } },
    { tag: 'line', attrs: { x1: '10.8', y1: '13.5', x2: '16.5', y2: '7.8' } },
  ],
  SCHOOL: [
    { tag: 'path', attrs: { d: 'M3 10 12 4l9 6-9 6-9-6Z' } },
    { tag: 'path', attrs: { d: 'M6 12v5c3 2 9 2 12 0v-5M21 10v6' } },
  ],
  SUPERMARKET: [
    { tag: 'circle', attrs: { cx: '9', cy: '20', r: '1' } },
    { tag: 'circle', attrs: { cx: '18', cy: '20', r: '1' } },
    { tag: 'path', attrs: { d: 'M3 4h2l2.2 10.2a2 2 0 0 0 2 1.6h7.9a2 2 0 0 0 1.9-1.4L21 8H6' } },
  ],
  DAYCARE_KINDERGARTEN: [
    { tag: 'path', attrs: { d: 'M3 11 12 4l9 7M5 10v10h14V10' } },
    { tag: 'circle', attrs: { cx: '12', cy: '12.5', r: '2' } },
    { tag: 'path', attrs: { d: 'M8.5 19c.5-2.3 1.7-3.5 3.5-3.5s3 1.2 3.5 3.5' } },
  ],
  ACADEMY: [
    { tag: 'path', attrs: { d: 'M4 5.5c3-.8 5.7-.3 8 1.4v12c-2.3-1.7-5-2.2-8-1.4v-12Z' } },
    { tag: 'path', attrs: { d: 'M12 6.9c2.3-1.7 5-2.2 8-1.4v8M12 6.9v12' } },
    { tag: 'path', attrs: { d: 'm16 19 4-4 1 1-4 4-2 .5.5-2Z' } },
  ],
  SUBWAY_STATION: [
    { tag: 'rect', attrs: { x: '5', y: '3', width: '14', height: '15', rx: '3' } },
    { tag: 'line', attrs: { x1: '8', y1: '7', x2: '16', y2: '7' } },
    { tag: 'line', attrs: { x1: '8', y1: '13', x2: '8.01', y2: '13' } },
    { tag: 'line', attrs: { x1: '16', y1: '13', x2: '16.01', y2: '13' } },
    { tag: 'path', attrs: { d: 'm8 18-2 3M16 18l2 3M8 21h8' } },
  ],
};

export function NearbyPlaceCategoryIcon({ category }: { category: NearbyPlaceCategory }) {
  return (
    <svg aria-hidden="true" fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.75" viewBox="0 0 24 24">
      {SHAPES[category].map((shape, index) => createElement(shape.tag, { ...shape.attrs, key: index }))}
    </svg>
  );
}

export function createNearbyPlaceCategoryIcon(category: NearbyPlaceCategory): SVGSVGElement {
  const namespace = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(namespace, 'svg');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('stroke-width', '1.75');
  svg.setAttribute('viewBox', '0 0 24 24');
  SHAPES[category].forEach((shape) => {
    const node = document.createElementNS(namespace, shape.tag);
    Object.entries(shape.attrs).forEach(([name, value]) => node.setAttribute(name, value));
    svg.append(node);
  });
  return svg;
}
