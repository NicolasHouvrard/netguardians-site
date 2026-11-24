import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { NavigationService, Tuile, lienPlugin } from '../../core/navigation/navigation.service';

/** Accueil : une carte par tuile autorisée (plugins actifs), des zones décidées par le serveur. */
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
    this.navigation.tuilesAutorisees().subscribe({
      // La zone de chaque tuile est décidée par le serveur (capacités de la société) : on affiche ce qu'il renvoie.
      next: liste => {
        this.tuiles.set(liste);
        this.charge.set(true);
      },
      error: () => {
        this.erreur.set(true);
        this.charge.set(true);
      },
    });
  }
}
