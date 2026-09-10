import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login.component')
      .then((module) => module.LoginComponent)
  },
  {
    path: 'dashboard',
    loadComponent: () => import('./features/dashboard/dashboard.component')
      .then((module) => module.DashboardComponent),
    canActivate: [authGuard]
  },
  {
    path: 'activity',
    loadComponent: () => import('./features/activity/activity.component')
      .then((module) => module.ActivityComponent),
    canActivate: [authGuard]
  },
  {
    path: 'receipts',
    loadComponent: () => import('./features/receipts/receipts.component')
      .then((module) => module.ReceiptsComponent),
    canActivate: [authGuard]
  },
  {
    path: 'orders',
    loadComponent: () => import('./features/orders/orders.component')
      .then((module) => module.OrdersComponent),
    canActivate: [authGuard]
  },
  {
    path: 'inventory',
    loadComponent: () => import('./features/inventory/inventory.component')
      .then((module) => module.InventoryComponent),
    canActivate: [authGuard]
  },
  {
    path: 'recipes',
    loadComponent: () => import('./features/recipes/recipes.component')
      .then((module) => module.RecipesComponent),
    canActivate: [authGuard]
  },
  {
    path: 'feedback',
    loadComponent: () => import('./features/feedback/feedback.component')
      .then((module) => module.FeedbackComponent),
    canActivate: [authGuard]
  },
  {
    path: 'settings',
    loadComponent: () => import('./features/settings/settings.component')
      .then((module) => module.SettingsComponent),
    canActivate: [authGuard]
  },
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: '**', redirectTo: 'dashboard' }
];
