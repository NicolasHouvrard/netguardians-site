import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { NavigationService, Tuile, ZoneNavigation, lienPlugin } from '../../core/navigation/navigation.service';

/** Accueil : une carte par tuile autorisée (plugins actifs), dans la zone de l'utilisateur. */
@Component({
  selector: 'app-accueil',
  standalone: true,
  imports: [RouterLink, MatIconModule],
  templateUrl: './accueil.component.html',
  styleUrl: './accueil.component.scss',
})
export class AccueilComponent implements OnInit {
  private readonly navigation = inject(NavigationService);

  protected readonly tuiles = signal<Tuile[]>([]);
  protected readonly charge = signal(false);
  protected readonly erreur = signal(false);
  protected readonly lien = lienPlugin;

  ngOnInit(): void {
    const zone: ZoneNavigation = this.navigation.zoneCourante();
    this.navigation.tuiles(zone).subscribe({
      // Défense en profondeur : jamais de lien vers une tuile d'une autre zone (ex. espace client -> PARTENAIRE).
      next: liste => {
        this.tuiles.set(liste.filter(t => t.zone === zone));
        this.charge.set(true);
      },
      error: () => {
        this.erreur.set(true);
        this.charge.set(true);
      },
    });
  }
}
