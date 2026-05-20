package com.arcclaw.core.agent.reasoning.error;

public class LlmExceptionClassifier {

    private static final LlmExceptionClassifierChain CHAIN;

    static {
        LlmExceptionClassifierChain gemini = new GeminiExceptionClassifier();
        LlmExceptionClassifierChain langChain = new LangChain4jExceptionClassifier();
        LlmExceptionClassifierChain generic = new GenericExceptionClassifier();
        
        gemini.setNext(langChain);
        langChain.setNext(generic);
        CHAIN = gemini;
    }

    public static LlmProviderException classify(Throwable throwable) {
        if (throwable instanceof LlmProviderException lpe) {
            return lpe;
        }
        return CHAIN.classify(throwable);
    }
}
