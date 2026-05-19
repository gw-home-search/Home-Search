import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App', () => {
  it('renders the scaffold shell', () => {
    const rootElement = document.createElement('div');

    act(() => {
      createRoot(rootElement).render(<App />);
    });

    expect(rootElement.textContent).toContain('Home Search');
  });
});
