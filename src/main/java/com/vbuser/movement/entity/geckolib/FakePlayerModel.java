package com.vbuser.movement.entity.geckolib;

import com.vbuser.movement.entity.FakePlayer;
import net.minecraft.util.ResourceLocation;
import software.bernie.geckolib3.model.AnimatedGeoModel;

public class FakePlayerModel extends AnimatedGeoModel<FakePlayer> {

    public static String idToCharacter(){
        return "fake_player";
    }

    @Override
    public ResourceLocation getModelLocation(FakePlayer fakePlayerModel) {
        return new ResourceLocation("genshin", "geo/"+ idToCharacter() +".geo.json");
    }

    @Override
    public ResourceLocation getTextureLocation(FakePlayer fakePlayerModel) {
        return new ResourceLocation("genshin", "textures/entity/"+ idToCharacter() +".png");
    }

    @Override
    public ResourceLocation getAnimationFileLocation(FakePlayer fakePlayerModel) {
        return new ResourceLocation("genshin","animations/"+idToCharacter()+".animation.json");
    }
}
