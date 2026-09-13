import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'sb-login',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  templateUrl: './login.html',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly submitting = signal(false);
  protected readonly failed = signal(false);

  /**
   * Ob es einen zweiten Anmeldeweg gibt.
   *
   * <p>Die Auskunft kommt vom Backend: Nur dort steht, ob ein Anbieter eingerichtet ist.
   */
  protected readonly providers = toSignal(this.auth.providers(), {
    initialValue: { oidcEnabled: false, displayName: null, authorizationUrl: null },
  });

  /**
   * Fehlschlag beim Anbieter.
   *
   * <p>Der Anbieter schickt den Browser mit einer Begründung hierher zurück. Ohne sie
   * stünde man wortlos wieder auf der Anmeldeseite und hielte es für einen Fehler der
   * Anwendung.
   */
  protected readonly providerError = this.route.snapshot.queryParamMap.get('fehler');

  protected readonly form = inject(FormBuilder).nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.failed.set(false);

    const { username, password } = this.form.getRawValue();

    this.auth.login(username, password).subscribe({
      next: (session) => {
        this.submitting.set(false);
        // Das Erstpasswort muss gewechselt werden, bevor irgendetwas anderes geht.
        if (session.mustChangePassword) {
          void this.router.navigate(['/passwort']);
          return;
        }
        const redirect = this.route.snapshot.queryParamMap.get('redirect') ?? '/';
        void this.router.navigateByUrl(redirect);
      },
      error: () => {
        this.submitting.set(false);
        this.failed.set(true);
        this.form.controls.password.reset();
      },
    });
  }
}
