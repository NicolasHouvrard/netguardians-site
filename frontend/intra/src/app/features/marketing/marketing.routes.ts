import { Routes } from '@angular/router';
import { MarketingLayoutComponent }     from './marketing-layout.component';
import { MarketingPagesComponent }      from './pages/marketing-pages.component';
import { MarketingPageEditorComponent } from './pages/editor/marketing-page-editor.component';
import { MarketingMenuComponent }       from './menu/marketing-menu.component';
import { MarketingCampaignsComponent }  from './campaigns/marketing-campaigns.component';
import { CampaignEditorComponent }      from './campaigns/campaign-editor.component';
import { SocialPostsComponent }         from './social/social-posts.component';
import { SocialPostEditorComponent }    from './social/social-post-editor.component';

export const MARKETING_ROUTES: Routes = [
  {
    path: '',
    component: MarketingLayoutComponent,
    children: [
      { path: '',              redirectTo: 'pages', pathMatch: 'full' },
      { path: 'pages',         component: MarketingPagesComponent },
      { path: 'pages/:id',     component: MarketingPageEditorComponent },
      { path: 'menu',          component: MarketingMenuComponent },
      { path: 'campaigns',     component: MarketingCampaignsComponent },
      { path: 'campaigns/:id', component: CampaignEditorComponent },
      { path: 'social',        component: SocialPostsComponent },
      { path: 'social/:id',    component: SocialPostEditorComponent },
    ],
  },
];
