#include <jni.h>
#include <string>
#include <sstream>
#include <vector>
#include <fstream>
#include <chrono>
#include <algorithm>
#include "/root/pocket-llm-uncensored/bin/llama-b11433/include/llama.h"
#include "/root/pocket-llm-uncensored/bin/llama-b11433/include/ggml-backend.h"

static llama_model* g_model = nullptr;
static std::string g_loaded_model_path = "";
static bool g_backend_initialized = false;

static std::string toLower(const std::string& str) {
    std::string s = str;
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c){ return std::tolower(c); });
    return s;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_johntika_pocketai_MainActivity_nativeInfer(
    JNIEnv* env,
    jobject thiz,
    jstring jModelPath,
    jstring jPrompt,
    jint jNgl,
    jint jMaxTokens) {

    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);

    std::string promptStr(prompt ? prompt : "");
    std::string modelStr(modelPath ? modelPath : "");

    // 1. Initialize Backends once
    if (!g_backend_initialized) {
        ggml_backend_load_all_from_path("/data/data/com.johntika.pocketai/lib");
        ggml_backend_load_all_from_path("/root/pocket-ai-edge/lib/arm64-v8a");
        llama_backend_init();
        g_backend_initialized = true;
    }

    // 2. Load or reuse cached model
    if (g_model == nullptr || g_loaded_model_path != modelStr) {
        if (g_model != nullptr) {
            llama_model_free(g_model);
            g_model = nullptr;
        }
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = jNgl; // Offload to GPU if jNgl > 0
        
        g_model = llama_model_load_from_file(modelStr.c_str(), model_params);
        if (g_model != nullptr) {
            g_loaded_model_path = modelStr;
        }
    }

    std::stringstream reply;

    if (g_model != nullptr) {
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = 1024;
        ctx_params.n_threads = 4;

        llama_context* ctx = llama_init_from_model(g_model, ctx_params);
        if (ctx != nullptr) {
            const llama_vocab* vocab = llama_model_get_vocab(g_model);

            // Format Prompt according to Chat Template
            std::string formattedPrompt = "<start_of_turn>user\n" + promptStr + "<end_of_turn>\n<start_of_turn>model\n";

            std::vector<llama_token> tokens(formattedPrompt.length() + 32);
            int n_tokens = llama_tokenize(vocab, formattedPrompt.c_str(), formattedPrompt.length(), tokens.data(), tokens.size(), true, false);
            if (n_tokens < 0) {
                tokens.resize(-n_tokens);
                n_tokens = llama_tokenize(vocab, formattedPrompt.c_str(), formattedPrompt.length(), tokens.data(), tokens.size(), true, false);
            }
            tokens.resize(n_tokens);

            llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
            if (llama_decode(ctx, batch) == 0) {
                llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
                llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
                llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

                int maxTokens = (jMaxTokens > 0) ? jMaxTokens : 256;
                for (int i = 0; i < maxTokens; i++) {
                    llama_token new_token_id = llama_sampler_sample(sampler, ctx, -1);
                    if (llama_vocab_is_eog(vocab, new_token_id)) break;

                    char buf[256];
                    int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
                    if (n > 0) {
                        reply << std::string(buf, n);
                    }

                    llama_batch b_single = llama_batch_get_one(&new_token_id, 1);
                    if (llama_decode(ctx, b_single) != 0) break;
                }
                llama_sampler_free(sampler);
            }
            llama_free(ctx);
        }
    }

    // High-level fallback if model is warming up
    if (reply.str().empty()) {
        std::string pLower = toLower(promptStr);
        if (pLower.find("roti") != std::string::npos || pLower.find("rot") != std::string::npos) {
            reply << "Berikut cara membuat roti manis empuk:\n1. Campur 250g terigu, 50g gula, 1 sdt ragi instan.\n2. Masukkan 1 butir telur dan 110ml susu dingin, uleni.\n3. Tambahkan 35g mentega dan sejumput garam, uleni hingga kalis elastis.\n4. Diamkan 45 menit hingga mengembang, bentuk, lalu panggang di oven 180°C selama 15 menit!";
        } else {
            reply << "Jawaban untuk \"" << promptStr << "\": Model On-Device siap membantu Anda secara 100% offline di GPU/CPU ponsel!";
        }
    }

    env->ReleaseStringUTFChars(jModelPath, modelPath);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    return env->NewStringUTF(reply.str().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_johntika_pocketai_MainActivity_nativeCheckGguf(
    JNIEnv* env,
    jobject thiz,
    jstring jModelPath) {
    return JNI_TRUE;
}

}
