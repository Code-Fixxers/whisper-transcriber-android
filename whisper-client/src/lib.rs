use std::str::FromStr;

use cdk::wallet::Wallet;
use cdk_common::amount::SplitTarget;
use cdk_common::nuts::Token;
use cdk_common::Amount;
use reqwest::Client;
use serde::Deserialize;

/// Error types for whisper transcription operations.
#[derive(Debug, thiserror::Error)]
pub enum WhisperError {
    #[error("insufficient payment: need {required} sats, got {provided} sats")]
    InsufficientPayment { required: u64, provided: u64 },
    #[error("invalid Cashu token or bad audio")]
    InvalidToken,
    #[error("missing authentication")]
    Unauthorized,
    #[error("invalid API key")]
    Forbidden,
    #[error("backend failure")]
    BackendError,
    #[error("audio error: {0}")]
    AudioError(String),
    #[error("network error: {0}")]
    NetworkError(#[from] reqwest::Error),
    #[error("json parse error: {0}")]
    ParseError(#[from] serde_json::Error),
    #[error("wallet error: {0}")]
    WalletError(String),
}

/// Options for transcription requests.
pub struct TranscribeOptions {
    pub language: Option<String>,
    pub prompt: Option<String>,
    pub response_format: Option<String>,
}

impl Default for TranscribeOptions {
    fn default() -> Self {
        Self {
            language: None,
            prompt: None,
            response_format: Some("json".into()),
        }
    }
}

/// Result of a successful transcription.
pub struct TranscribeResult {
    pub text: String,
    /// Cashu change token from X-Cashu-Change header, if any.
    pub change_token: Option<String>,
}

/// Async client for a paid Whisper transcription API.
pub struct WhisperClient {
    base_url: String,
    http: Client,
}

#[derive(Deserialize)]
struct TranscribeResponse {
    text: String,
}

#[derive(Deserialize)]
struct ErrorResponse {
    error: String,
}

impl WhisperClient {
    /// Create a new client. `base_url` is e.g. `"https://whisper.bitp.cz"`.
    pub fn new(base_url: String) -> Self {
        Self {
            base_url: base_url.trim_end_matches('/').to_string(),
            http: Client::new(),
        }
    }

    /// Transcribe audio using API key authentication (no payment).
    pub async fn transcribe_with_key(
        &self,
        api_key: &str,
        audio_data: Vec<u8>,
        file_name: &str,
        options: TranscribeOptions,
    ) -> Result<TranscribeResult, WhisperError> {
        let form = self.build_form(audio_data, file_name, &options);

        let resp = self
            .http
            .post(self.endpoint())
            .header("Authorization", format!("Bearer {api_key}"))
            .multipart(form)
            .send()
            .await?;

        self.handle_response(resp, None).await
    }

    /// Transcribe audio using Cashu ecash payment.
    ///
    /// `wallet` must be initialized against the same mint the server uses.
    /// `amount_sats` is the number of sats to attach as payment.
    /// If the server returns 402 (insufficient payment), the error includes
    /// the required amount so the caller can retry with a higher value.
    pub async fn transcribe_with_cashu(
        &self,
        wallet: &Wallet,
        amount_sats: u64,
        audio_data: Vec<u8>,
        file_name: &str,
        options: TranscribeOptions,
    ) -> Result<TranscribeResult, WhisperError> {
        let amount = Amount::from(amount_sats);

        let prepared = wallet
            .prepare_send(amount, Default::default())
            .await
            .map_err(|e| WhisperError::WalletError(e.to_string()))?;

        let token = wallet
            .send(prepared, None)
            .await
            .map_err(|e| WhisperError::WalletError(e.to_string()))?;

        let token_str = token.to_string();
        let form = self.build_form(audio_data, file_name, &options);

        let resp = self
            .http
            .post(self.endpoint())
            .header("X-Cashu", &token_str)
            .multipart(form)
            .send()
            .await?;

        self.handle_response(resp, Some(wallet)).await
    }

    fn endpoint(&self) -> String {
        format!("{}/v1/audio/transcriptions", self.base_url)
    }

    fn build_form(
        &self,
        audio_data: Vec<u8>,
        file_name: &str,
        options: &TranscribeOptions,
    ) -> reqwest::multipart::Form {
        let file_part = reqwest::multipart::Part::bytes(audio_data)
            .file_name(file_name.to_string())
            .mime_str("application/octet-stream")
            .unwrap();

        let mut form = reqwest::multipart::Form::new()
            .part("file", file_part)
            .text("model", "whisper-1");

        if let Some(fmt) = &options.response_format {
            form = form.text("response_format", fmt.clone());
        }
        if let Some(lang) = &options.language {
            form = form.text("language", lang.clone());
        }
        if let Some(prompt) = &options.prompt {
            form = form.text("prompt", prompt.clone());
        }

        form
    }

    async fn handle_response(
        &self,
        resp: reqwest::Response,
        wallet: Option<&Wallet>,
    ) -> Result<TranscribeResult, WhisperError> {
        let status = resp.status();

        match status.as_u16() {
            200 => {
                let change_token = resp
                    .headers()
                    .get("X-Cashu-Change")
                    .and_then(|v| v.to_str().ok())
                    .map(|s| s.to_string());

                // Best-effort: receive change back into wallet
                if let (Some(w), Some(ref token_str)) = (wallet, &change_token) {
                    receive_token_into_wallet(w, token_str).await;
                }

                let body: TranscribeResponse = resp.json().await?;
                Ok(TranscribeResult {
                    text: body.text,
                    change_token,
                })
            }
            401 => Err(WhisperError::Unauthorized),
            402 => {
                let refund_token = extract_header(&resp, "X-Cashu-Refund");
                if let (Some(w), Some(ref token_str)) = (wallet, &refund_token) {
                    receive_token_into_wallet(w, token_str).await;
                }

                let body = resp.text().await.unwrap_or_default();
                let (required, provided) = parse_insufficient_payment(&body);
                Err(WhisperError::InsufficientPayment { required, provided })
            }
            403 => Err(WhisperError::Forbidden),
            400 => {
                let body = resp.text().await.unwrap_or_default();
                if body.contains("token") || body.contains("Token") {
                    Err(WhisperError::InvalidToken)
                } else {
                    Err(WhisperError::AudioError(body))
                }
            }
            502 => {
                let refund_token = extract_header(&resp, "X-Cashu-Refund");
                if let (Some(w), Some(ref token_str)) = (wallet, &refund_token) {
                    receive_token_into_wallet(w, token_str).await;
                }
                Err(WhisperError::BackendError)
            }
            _ => Err(WhisperError::AudioError(format!(
                "unexpected status: {status}"
            ))),
        }
    }
}

fn extract_header(resp: &reqwest::Response, name: &str) -> Option<String> {
    resp.headers()
        .get(name)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string())
}

fn parse_insufficient_payment(body: &str) -> (u64, u64) {
    // Expected: {"error": "need X sats, got Y sats"}
    if let Ok(err) = serde_json::from_str::<ErrorResponse>(body) {
        let nums: Vec<u64> = err
            .error
            .split_whitespace()
            .filter_map(|w| w.parse().ok())
            .collect();
        if nums.len() >= 2 {
            return (nums[0], nums[1]);
        }
    }
    (0, 0)
}

async fn receive_token_into_wallet(wallet: &Wallet, token_str: &str) {
    match Token::from_str(token_str) {
        Ok(token) => {
            let proofs = token.proofs();
            if let Err(e) =
                wallet
                    .receive_proofs(proofs, SplitTarget::default(), &[], &[])
                    .await
            {
                tracing::warn!("failed to receive token into wallet: {e}");
            }
        }
        Err(e) => {
            tracing::warn!("failed to parse token: {e}");
        }
    }
}
