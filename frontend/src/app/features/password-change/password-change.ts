import { Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'sb-password-change',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './password-change.html',
})
export class PasswordChange {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly submitting = signal(false);
  protected readonly forced = this.auth.mustChangePassword;

  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      currentPassword: ['', Validators.required],
      // Laenge statt Zeichenklassen: erzwungene Sonderzeichen fuehren erfahrungsgemaess
      // zu schlechteren, aufgeschriebenen Passwoertern.
      newPassword: ['', [Validators.required, Validators.minLength(12)]],
      repeatPassword: ['', Validators.required],
    },
    { validators: passwordsMatch },
  );

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      return;
    }
    this.submitting.set(true);

    const { currentPassword, newPassword } = this.form.getRawValue();

    this.auth.changePassword(currentPassword, newPassword).subscribe({
      next: () => {
        this.submitting.set(false);
        // Das Backend beendet die Sitzung; eine erneute Anmeldung ist Absicht.
        this.snackBar.open('Passwort geaendert. Bitte neu anmelden.', 'OK', { duration: 8000 });
        void this.router.navigate(['/login']);
      },
      error: () => this.submitting.set(false),
    });
  }
}

function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  const newPassword = group.get('newPassword')?.value;
  const repeat = group.get('repeatPassword')?.value;
  return !repeat || newPassword === repeat ? null : { passwordsDiffer: true };
}
