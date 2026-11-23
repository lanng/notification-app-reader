package br.com.c6tag.diagnostico.remote.access

/** A Firebase sign-in candidate is not authorization for a remote host. */
internal sealed interface AccountState {
    data object SignedOut : AccountState

    data class SignedInCandidate(
        val account: OwnerAccount,
    ) : AccountState
}

internal fun OwnerAccount?.toAccountState(): AccountState =
    this?.let(AccountState::SignedInCandidate) ?: AccountState.SignedOut
