import { describe, it, expect, beforeEach } from 'vitest';
import { loadSavedConnections, saveConnectionProfile, deleteSavedConnection } from './savedConnections';

describe('savedConnections', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('nunca persiste la contraseña de la conexión', () => {
    const updated = saveConnectionProfile([], {
      profileName: 'Producción',
      engineType: 'POSTGRESQL',
      host: 'db.example.com',
      port: 5432,
      databaseName: 'ventas',
      username: 'admin',
      password: 's3cr3t-db-password'
    });

    expect(updated[0]).not.toHaveProperty('password');
    expect(JSON.stringify(updated)).not.toContain('s3cr3t-db-password');

    const persisted = JSON.parse(window.localStorage.getItem('pushdb_saved_connections'));
    expect(persisted[0]).not.toHaveProperty('password');
  });

  it('conserva los demás campos no sensibles', () => {
    const updated = saveConnectionProfile([], {
      profileName: 'Producción',
      engineType: 'POSTGRESQL',
      host: 'db.example.com',
      port: 5432,
      databaseName: 'ventas',
      username: 'admin',
      password: 'secret'
    });

    expect(updated[0]).toMatchObject({
      profileName: 'Producción',
      engineType: 'POSTGRESQL',
      host: 'db.example.com',
      port: 5432,
      databaseName: 'ventas',
      username: 'admin'
    });
  });

  it('reemplaza un perfil existente con el mismo nombre en vez de duplicarlo', () => {
    let connections = saveConnectionProfile([], { profileName: 'BD Local', host: 'a', password: 'x' });
    connections = saveConnectionProfile(connections, { profileName: 'BD Local', host: 'b', password: 'y' });

    expect(connections).toHaveLength(1);
    expect(connections[0].host).toBe('b');
  });

  it('loadSavedConnections lee lo persistido en localStorage', () => {
    saveConnectionProfile([], { profileName: 'BD Local', host: 'a', password: 'x' });

    expect(loadSavedConnections()).toHaveLength(1);
  });

  it('loadSavedConnections retorna [] si el JSON guardado está corrupto', () => {
    window.localStorage.setItem('pushdb_saved_connections', '{not-json');

    expect(loadSavedConnections()).toEqual([]);
  });

  it('deleteSavedConnection elimina solo el perfil indicado', () => {
    let connections = saveConnectionProfile([], { profileName: 'A', id: '1', password: 'x' });
    connections = saveConnectionProfile(connections, { profileName: 'B', id: '2', password: 'y' });

    const updated = deleteSavedConnection(connections, '1');

    expect(updated).toHaveLength(1);
    expect(updated[0].profileName).toBe('B');
  });
});
