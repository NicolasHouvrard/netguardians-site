import { resoudre } from './navigation';

describe('resoudre', () => {
  it('route la liste, la creation et la fiche', () => {
    expect(resoudre('/clients')).toEqual({ ecran: 'liste' });
    expect(resoudre('/clients/')).toEqual({ ecran: 'liste' });
    expect(resoudre('/')).toEqual({ ecran: 'liste' });
    expect(resoudre('')).toEqual({ ecran: 'liste' });
    expect(resoudre('/clients/nouveau')).toEqual({ ecran: 'creation' });
    expect(resoudre('/clients/42')).toEqual({ ecran: 'fiche', societeId: 42 });
  });

  it('refuse tout le reste (aucun identifiant non numerique ne part vers l API)', () => {
    expect(resoudre('/clients/../etc')).toEqual({ ecran: 'inconnu' });
    expect(resoudre('/clients/12abc')).toEqual({ ecran: 'inconnu' });
    expect(resoudre('/clients/1/2')).toEqual({ ecran: 'inconnu' });
    expect(resoudre('/autre')).toEqual({ ecran: 'inconnu' });
  });
});
