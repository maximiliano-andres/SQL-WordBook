import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent, screen } from '@testing-library/react';
import { useModalA11y } from './useModalA11y';

function TestModal({ isOpen, onClose }) {
  const ref = useModalA11y(isOpen, onClose);
  if (!isOpen) return null;
  return (
    <div ref={ref} role="dialog" aria-modal="true" tabIndex={-1}>
      <button>Primero</button>
      <button>Segundo</button>
      <button>Último</button>
    </div>
  );
}

describe('useModalA11y', () => {
  it('enfoca el primer elemento interactivo al abrir', () => {
    render(<TestModal isOpen onClose={vi.fn()} />);
    expect(screen.getByText('Primero')).toHaveFocus();
  });

  it('llama a onClose al presionar Escape', () => {
    const onClose = vi.fn();
    render(<TestModal isOpen onClose={onClose} />);

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('no falla si onClose es null al presionar Escape', () => {
    render(<TestModal isOpen onClose={null} />);
    expect(() => fireEvent.keyDown(document, { key: 'Escape' })).not.toThrow();
  });

  it('Tab desde el último elemento vuelve al primero (foco atrapado)', () => {
    render(<TestModal isOpen onClose={vi.fn()} />);
    screen.getByText('Último').focus();

    fireEvent.keyDown(document, { key: 'Tab' });

    expect(screen.getByText('Primero')).toHaveFocus();
  });

  it('Shift+Tab desde el primer elemento va al último (foco atrapado)', () => {
    render(<TestModal isOpen onClose={vi.fn()} />);
    screen.getByText('Primero').focus();

    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });

    expect(screen.getByText('Último')).toHaveFocus();
  });
});
