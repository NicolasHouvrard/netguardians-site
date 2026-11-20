import { resoudre } from './navigation';

describe('resoudre', () => {
  it('reconnait les ecrans', () => {
    expect(resoudre('/partenaires')).toEqual({ ecran: 'liste' });
    expect(resoudre('/partenaires/nouveau')).toEqual({ ecran: 'creation' });
    expect(resoudre('/partenaires/modele')).toEqual({ ecran: 'modele' });
    expect(resoudre('/partenaires/12/clients/nouveau')).toEqual({ ecran: 'client', partenaireId: '12' });
  });
  it('refuse tout identifiant non numerique ou trop long', () => {
    expect(resoudre('/partenaires/abc/clients/nouveau')).toEqual({ ecran: 'inconnu' });
    expect(resoudre('/partenaires/1;2/clients/nouveau')).toEqual({ ecran: 'inconnu' });
    expect(resoudre('/partenaires/1234567890123456/clients/nouveau')).toEqual({ ecran: 'inconnu' });
    expect(resoudre('/partenaires/../clients/nouveau')).toEqual({ ecran: 'inconnu' });
    expect(resoudre('/autre')).toEqual({ ecran: 'inconnu' });
  });
});
