package com.logicielpourtous.site.features.clients;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.SocieteIdentiteRepository;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;

/**
 * Annuaire clients offert aux plugins. Accès aux données seulement ({@code clientsDuPartenaire}, {@code fiche}) :
 * l'autorisation passe par {@link #autorise}, qui délègue sans raccourci à {@link DroitsService}.
 */
@Component
class AnnuaireClientsContratImpl implements AnnuaireClientsContrat {

	private final LienClientRepository liens;

	private final CapaciteSocieteRepository capacites;

	private final SocieteIdentiteRepository societes;

	private final DroitsService droits;

	AnnuaireClientsContratImpl(LienClientRepository liens, CapaciteSocieteRepository capacites,
			SocieteIdentiteRepository societes, DroitsService droits) {
		this.liens = liens;
		this.capacites = capacites;
		this.societes = societes;
		this.droits = droits;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ClientResume> clientsDuPartenaire(Long partenaireSocieteId) {
		if (partenaireSocieteId == null) {
			throw new IllegalArgumentException("La société partenaire doit être renseignée.");
		}
		List<LienClient> lies = liens.findByPartenaireSocieteId(partenaireSocieteId);
		if (lies.isEmpty()) {
			return List.of();
		}
		Set<Long> ids = lies.stream().map(LienClient::getClientSocieteId).collect(Collectors.toSet());
		Set<Long> clients = capacites.findBySocieteIdIn(ids)
			.stream()
			.filter(c -> c.getCapacite() == TypeCapacite.CLIENT)
			.map(CapaciteSociete::getSocieteId)
			.collect(Collectors.toSet());
		Map<Long, SocieteIdentite> parId = societes.findAllById(clients)
			.stream()
			.collect(Collectors.toMap(SocieteIdentite::getId, Function.identity()));
		return lies.stream()
			.filter(l -> parId.containsKey(l.getClientSocieteId()))
			.map(l -> resume(parId.get(l.getClientSocieteId()), l))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<FicheClient> fiche(Long clientSocieteId) {
		if (clientSocieteId == null) {
			throw new IllegalArgumentException("La société cliente doit être renseignée.");
		}
		Optional<LienClient> lien = liens.findByClientSocieteId(clientSocieteId);
		if (lien.isEmpty()) {
			return Optional.empty();
		}
		List<String> noms = capacites.findBySocieteId(clientSocieteId)
			.stream()
			.map(c -> c.getCapacite().name())
			.sorted()
			.toList();
		if (!noms.contains(TypeCapacite.CLIENT.name())) {
			return Optional.empty();
		}
		return societes.findById(clientSocieteId)
			.map(s -> new FicheClient(resume(s, lien.get()), lien.get().getPartenaireSocieteId(), noms));
	}

	@Override
	public boolean autorise(Long userId, Long societeId, String droit) {
		return droits.autorise(userId, societeId, droit);
	}

	private static ClientResume resume(SocieteIdentite s, LienClient lien) {
		return new ClientResume(s.getId(), s.getSiren(), s.getTva(), lien.getOrigine());
	}

}
