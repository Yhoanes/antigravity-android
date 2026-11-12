#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic patcher for web/main.js (Antigravity v2.29.0 UI).

Applies:
1. Android Bridge hooks (Theme sync via window.Android + Desktop Mode toggle in Appearance).
2. Original PC AuxPane v2 redesign (Search tabs/files/plugins/subagents/artifacts/tasks)
   and the header Overview floating popover button ([📄] OverviewToggleIconButton) on Mobile & Desktop.
3. Original PC Customizations (Marketplace | Installed) and Automations inside Settings (⚙️).
"""

import sys
import os


def must_replace(content, old, new, label):
    if old not in content:
        raise RuntimeError(f"[web-patch] target not found for {label}")
    return content.replace(old, new, 1)


def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    if "Android.getTheme" in content and "__agLoadMarketplaceCatalog" in content:
        updated = False
        if 'initialPath:b?b.fsPath:$g?"C:/":"/"' in content:
            content = must_replace(
                content,
                'initialPath:b?b.fsPath:$g?"C:/":"/"',
                'initialPath:"/sdcard"',
                "wqc_default_sdcard_path",
            )
            updated = True
            print("[web-patch] Patched initialPath to /sdcard")
        if "puc({cascadeId:M,planModel:y?.value,enabled:!xa&&me&&(la??AP).voice})" in content:
            content = must_replace(
                content,
                "puc({cascadeId:M,planModel:y?.value,enabled:!xa&&me&&(la??AP).voice})",
                "puc({cascadeId:M,planModel:y?.value||1018,enabled:!xa&&me&&(la??AP).voice})",
                "puc_planModel_fallback",
            )
            updated = True
            print("[web-patch] Patched puc planModel fallback to 1018")
        if "Di=Ce=>{var Gg=Mc.current?.getRootElement();Gg&&document.activeElement===Gg&&!Gg.contains(Ce.target)&&Ce.preventDefault()}" in content:
            content = must_replace(
                content,
                "Di=Ce=>{var Gg=Mc.current?.getRootElement();Gg&&document.activeElement===Gg&&!Gg.contains(Ce.target)&&Ce.preventDefault()}",
                'Di=Ce=>{var Gg=Mc.current?.getRootElement();Gg&&document.activeElement===Gg&&!Gg.contains(Ce.target)&&!(Ce.target instanceof Element&&Ce.target.closest(\'button,[role="button"],[aria-haspopup],[data-testid="project-selector-trigger"],[data-testid="model-selector-trigger"]\'))&&Ce.preventDefault()}',
                "QOc_Di_allow_menu_triggers",
            )
            updated = True
            print("[web-patch] Patched QOc pointerdown Di to allow menu/button triggers while editor focused")
        old_bw = (
            'return{onPointerDown(x){p.current=x.pointerType},\n'
            'onMouseDown(x){var y=p.current,z=x.nativeEvent,A=m.select("open");if(!(x.button!==0||d==="click"||Iv(y,!0)&&g)){var D=w(A,x.currentTarget,F=>F==="click"||F==="mousedown");A=Av(z);if(Ev(A))v(D,z,A,y);else{var C=x.currentTarget;q.request(()=>{v(D,z,C,y)})}}},onClick(x){if(d!=="mousedown-only"){var y=p.current;if(d==="mousedown"&&y)p.current=void 0;else if(!Iv(y,!0)||!g){var z=m.select("open");z=w(z,x.currentTarget,A=>A==="click"||A==="mousedown"||A==="keydown"||A==="keyup");v(z,x.nativeEvent,x.currentTarget,\n'
            'y)}}},onKeyDown(){p.current=void 0}}'
        )
        new_bw = (
            'return{onPointerDown(x){p.current=x.pointerType;p.md=!1},\n'
            'onMouseDown(x){var y=p.current,z=x.nativeEvent,A=m.select("open");if(!(x.button!==0||d==="click"||Iv(y,!0)&&g)){p.md=!0;var D=w(A,x.currentTarget,F=>F==="click"||F==="mousedown");A=Av(z);if(Ev(A))v(D,z,A,y);else{var C=x.currentTarget;q.request(()=>{v(D,z,C,y)})}}},onClick(x){if(d!=="mousedown-only"){var y=p.current;if(d==="mousedown"&&y&&p.md)p.current=void 0,p.md=!1;else if(p.current=void 0,p.md=!1,!Iv(y,!0)||!g){var z=m.select("open");z=w(z,x.currentTarget,A=>A==="click"||A==="mousedown"||A==="keydown"||A==="keyup");v(z,x.nativeEvent,x.currentTarget,\n'
            'y)}}},onKeyDown(){p.current=void 0;p.md=!1}}'
        )
        if old_bw in content:
            content = must_replace(content, old_bw, new_bw, "Bw_click_fallback_when_mousedown_prevented")
            updated = True
            print("[web-patch] Patched Base UI Bw trigger to fallback to onClick if mousedown was suppressed")
        old_vzb = "async function VZb(a){var b=e,c=e;try{a.isStreaming()?c(await b(a.refreshSkills())):c(await b(a.refresh()))}finally{b()}}"
        new_vzb = "async function VZb(a){var b=e,c=e;try{c(await b(a.refresh()))}finally{b()}}"
        if old_vzb in content:
            content = must_replace(content, old_vzb, new_vzb, "VZb_always_refresh_plugins_and_skills")
            updated = True
            print("[web-patch] Patched VZb to always refresh plugins and skills after Marketplace mutations")
        if updated:
            with open(file_path, "w", encoding="utf-8", newline="\n") as f:
                f.write(content)
        else:
            print("[web-patch] main.js already patched (v2.31.1 Android UI + Smart Marketplace Fusion V3 + /sdcard + planModel fallback)")
        return True

    # 1a. pY() system theme resolver via window.Android.getTheme()
    content = must_replace(
        content,
        'function pY(){return typeof window!=="undefined"&&window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}',
        'function pY(){try{if(typeof window!=="undefined"&&window.Android&&typeof window.Android.getTheme==="function"){var _t=window.Android.getTheme();if(_t==="dark")return!0;if(_t==="light")return!1}if(typeof window!=="undefined"&&window.__AG_RESOLVED_THEME__){if(window.__AG_RESOLVED_THEME__==="dark")return!0;if(window.__AG_RESOLVED_THEME__==="light")return!1}}catch(_e){}return typeof window!=="undefined"&&window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}',
        "pY_system_theme",
    )

    # 1b. nZc(a) hostTheme=system resolver + unconditional startup call
    content = must_replace(
        content,
        'function nZc(a){if(typeof window!=="undefined"){var b=(new URLSearchParams(window.location.search)).get("hostTheme");b!=="dark"&&b!=="light"||Si.setState(b);',
        'function nZc(a){if(typeof window!=="undefined"){var b=(new URLSearchParams(window.location.search)).get("hostTheme");b==="system"&&(b=typeof window!=="undefined"&&window.Android&&window.Android.getTheme?window.Android.getTheme():(typeof window!=="undefined"?window.__AG_RESOLVED_THEME__:void 0));b!=="dark"&&b!=="light"||Si.setState(b);',
        "nZc_hostTheme",
    )
    content = must_replace(
        content,
        "d&&nZc(f?.router);",
        "nZc(f?.router);",
        "nZc_startup_call",
    )

    # 1c. fZc theme sync (window.Android.setTheme, __agOnSystemThemeChanged, setThemeByUser)
    content = must_replace(
        content,
        'z=E=>{m.setState(E);E=qY({hostTheme:b?Si.getState():void 0,themeMode:E});var F=t.getState();F=E===2?F.light:F.dark;var G=rY(E);hY(G,F,E,b);l.setState(G);y(G)}',
        'z=E=>{m.setState(E);var _origE=E;E=qY({hostTheme:b?Si.getState():void 0,themeMode:E});var F=t.getState();F=E===2?F.light:F.dark;var G=rY(E);hY(G,F,E,b);l.setState(G);try{var _m=G.uiTheme==="vs-dark"?"dark":"light",_thm=b||_origE!==1?_m:"system";window.__agSelectedTheme=_thm;window.Android&&window.Android.setTheme&&window.Android.setTheme(_thm)}catch(_e){}y(G)}',
        "fZc_setTheme",
    )
    content = must_replace(
        content,
        ':window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change",()=>{m.getState()===1&&z(1)});',
        ':(c=()=>{if(c._updating)return;c._updating=!0;try{var _cur=window.Android&&window.Android.getTheme?window.Android.getTheme():window.__AG_RESOLVED_THEME__;if((_cur==="dark"||_cur==="light")&&Si.getState()!==_cur)Si.setState(_cur)}catch(_e){}try{m.getState()===1&&z(1)}catch(_e){}try{typeof window!=="undefined"&&window.dispatchEvent(typeof CustomEvent==="function"?new CustomEvent("__ag_theme_change"):new Event("__ag_theme_change"))}catch(_e){}c._updating=!1},c._updating=!1,window.__agOnSystemThemeChanged=c,Si.onDidChange(()=>{c._updating||c()}),window.matchMedia&&window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change",c));',
        "fZc_system_listener",
    )
    content = must_replace(
        content,
        "pushUpdate:E=>{switch(E){case 2:E=2;break;case 3:E=1;break;case 1:E=3;break;default:E=1}hZc(a,E)}",
        'pushUpdate:E=>{var _orig=E;switch(E){case 2:E=2;break;case 3:E=1;break;case 1:E=3;break;default:E=1}hZc(a,E);z(_orig);try{var _um=_orig===2?"light":(_orig===3?"dark":"system");window.Android&&window.Android.setThemeByUser&&window.Android.setThemeByUser(_um)}catch(_e){}}',
        "fZc_pushUpdate",
    )

    # 1d. Desktop Mode toggle in Appearance settings
    content = must_replace(
        content,
        "var G=a!==3,H=a!==2,I=J=>L=>{J===w&&z(L)};",
        "var [isDesktop,setIsDesktop]=(0,A.useState)(()=>{try{if(window.Android&&typeof window.Android.isDesktopMode==='function')return window.Android.isDesktopMode();var s=localStorage.getItem('ag_desktop_mode');return s===null?!1:s==='true'}catch(_){return !1}});"
        "var onDesktopToggle=(0,A.useCallback)(v=>{setIsDesktop(v);try{localStorage.setItem('ag_desktop_mode',String(v));if(window.Android&&typeof window.Android.setDesktopMode==='function')window.Android.setDesktopMode(v)}catch(_){}},[]);"
        "var G=a!==3,H=a!==2,I=J=>L=>{J===w&&z(L)};",
        "Appearance_desktop_hooks",
    )
    content = must_replace(
        content,
        'A.createElement(Vsb,{settingKey:"contrast",\nlabel:"Contrast",description:"",options:RYc,resolveOptionToString:SYc}),',
        'A.createElement(Vsb,{settingKey:"contrast",\nlabel:"Contrast",description:"",options:RYc,resolveOptionToString:SYc}),'
        'A.createElement(XP,{label:"Desktop Mode",description:"Use desktop viewport and user agent for the workspace",rightElement:A.createElement($P,{checked:isDesktop,onCheckedChange:onDesktopToggle})}),',
        "Appearance_desktop_jsx",
    )

    # 2. Enable AuxPane v2 redesign + Overview [📄] popover button (zBc) on Mobile & Desktop
    content = must_replace(
        content,
        'auxPaneComponent:{feature:{overviewPanelMode:"outputs"}}',
        'auxPaneComponent:{feature:{overviewPanelMode:"full",redesignEnabled:!0}}',
        "auxPane_V7c",
    )
    content = must_replace(
        content,
        'auxPaneComponent:{feature:{location:"sidebar",\nredesignEnabled:!1,bottomTerminalPanelEnabled:!1,overviewPanelMode:"full"}',
        'auxPaneComponent:{feature:{location:"sidebar",\nredesignEnabled:!0,bottomTerminalPanelEnabled:!1,overviewPanelMode:"full"}',
        "auxPane_main",
    )
    content = must_replace(
        content,
        'function r7c(){return sj()?!1:typeof window!=="undefined"&&window.electronNative?!0:!qka()};function s7c(a,b){try{let c=window.localStorage.getItem("jetski.developer.customFlagOverrides"),d=c?ok(c)["enable-aux-pane-redesign"]:void 0;d!==void 0?d&&r7c()&&b():Cj()&&r7c()?b():a.initPromise.then(()=>{jj(a,"enable-aux-pane-redesign")&&r7c()&&b()}).catch(()=>{})}catch{}}',
        'function r7c(){return!sj()};function s7c(a,b){try{r7c()&&b()}catch{}}',
        "r7c_s7c",
    )
    content = must_replace(
        content,
        '!b&&A.createElement(A.Fragment,null,A.createElement(u5,{cascadeId:p}),A.createElement(YT,{iconName:"dock_to_bottom",onClick:r,"aria-label":"Toggle Side Panel",dataTestId:"mobile-toggle-aux-sidebar",size:"icon-touch"}))',
        '!b&&A.createElement(A.Fragment,null,A.createElement(u5,{cascadeId:p}),A.createElement(zBc,null),A.createElement(YT,{iconName:"dock_to_bottom",onClick:r,"aria-label":"Toggle Side Panel",dataTestId:"mobile-toggle-aux-sidebar",size:"icon-touch"}))',
        "mobile_header_zBc",
    )
    content = must_replace(
        content,
        'l=l?A.createElement(pJc,{content:R,sidePanel:S,statusBar:W}):A.createElement(yJc,',
        'l=l?A.createElement(pJc,{content:y,sidePanel:S,statusBar:W}):A.createElement(yJc,',
        "mobile_pJc_with_AIc_overview_host",
    )
    content = must_replace(
        content,
        'style:{width:320,maxWidth:"calc(100% - 16px)"}},A.createElement(wBc,{cascadeId:d,treeId:h}))):A.createElement(A.Fragment,null,a)};',
        'style:{width:320,maxWidth:"calc(100% - 16px)"}},A.createElement(wBc,{cascadeId:d,treeId:h,onOpened:x==="small"?()=>c.setOverlayOpen(!1):void 0}))):A.createElement(A.Fragment,null,a)};',
        "AIc_close_overlay_on_item_open",
    )
    content = must_replace(
        content,
        'function zzc(a,b){a=b??a;',
        'function zzc(a,b){a=a??b;',
        "zzc_keep_open_when_count_positive",
    )
    content = must_replace(
        content,
        'function Azc({open:a}){return A.createElement(lt,{name:"chevron_right",size:16,className:dj("text-muted-foreground group-hover:text-secondary-foreground transition-all duration-150 opacity-0 group-hover:opacity-100",a?"rotate-90":"")})}',
        'function Azc({open:a}){return A.createElement(lt,{name:"chevron_right",size:16,className:dj("text-muted-foreground group-hover:text-secondary-foreground transition-all duration-150 opacity-70 group-hover:opacity-100",a?"rotate-90":"")})}',
        "Azc_visible_chevron_on_touch",
    )
    content = must_replace(
        content,
        '{id:"modifiedFiles",title:"Modified Files",defaultEnabled:!1}',
        '{id:"modifiedFiles",title:"Modified Files",defaultEnabled:!0}',
        "enable_modifiedFiles_by_default",
    )
    content = must_replace(
        content,
        '{type:"fileView",id:"open-file",title:"Open File",icon:A.createElement(lt,{name:"draft",size:16}),onSelect:({logEvent:a,commandsService:b})=>{a("aux_new_tab_open_file_click");\nb?.executeCommand("openCommandPalette")}}',
        '{type:"fileView",id:"open-file",title:"Open File",icon:A.createElement(lt,{name:"draft",size:16}),onSelect:({logEvent:a,openAuxTab:b,treeId:c})=>{a("aux_new_tab_open_file_click");\nb({type:"fileView",fileView:{}},void 0,{treeId:c,keepOpen:!0,source:"new_tab_card"})}}',
        "open_file_card",
    )
    content = must_replace(
        content,
        'terminalFeature:{feature:null,important:[{runtimeGating:{host:[Xi.Extension]},feature:null}]',
        'terminalFeature:{feature:{terminalCapabilities:lW},important:[{runtimeGating:{host:[Xi.Extension]},feature:null}]',
        "terminalFeature_main",
    )
    content = must_replace(
        content,
        'fileExplorerTab:{feature:{isEnabled:!1},',
        'fileExplorerTab:{feature:{isEnabled:!0},',
        "fileExplorerTab_main",
    )

    # 3. Enable Plugins/Marketplace & Automations and wire them inside Settings (⚙️)
    content = must_replace(
        content,
        'plugins:{feature:!1,applyAll:[{runtimeGating:{customFlag:"enable-plugins"},feature:!0}]}',
        'plugins:{feature:!0,applyAll:[{runtimeGating:{customFlag:"enable-plugins"},feature:!0}]}',
        "plugins_feature",
    )
    content = must_replace(
        content,
        'x=Oi(kj(b,"enable-sidecars"),E=>k&&(l||E));',
        'x=new Ni(!0);',
        "extensibility_x",
    )
    content = must_replace(
        content,
        'showUISidecars:kj(b,"enable-ui-sidecars"),showDeclaredPermissions:kj(b,"jetski-sidecar-declared-permissions"),enableAutomations:kj(b,\n"scheduled-tasks-v2")',
        'showUISidecars:new Ni(!0),showDeclaredPermissions:kj(b,"jetski-sidecar-declared-permissions"),enableAutomations:new Ni(!0)',
        "extensibility_flags",
    )
    content = must_replace(
        content,
        'deletePlugin:async t=>f(async v=>{var w=$b(aia);w.pluginId=t.pluginId;return v.deletePlugin(w)}),listPluginAuthProviders:async()=>f(t=>t.listPluginAuthProviders($b(bia,{}))),',
        'deletePlugin:async t=>{try{var _dr=await f(async v=>{var w=$b(aia);w.pluginId=t.pluginId;return v.deletePlugin(w)});if(_dr&&_dr.success!==!1)return _dr}catch(_e){}try{if(window.Android&&window.Android.uninstallMarketplacePlugin&&window.Android.uninstallMarketplacePlugin(t.pluginId)){await new Promise(r=>setTimeout(r,250));return{success:!0,message:""}}}catch(_e2){}return{success:!1,message:"Failed to delete plugin"}},listPluginAuthProviders:async()=>({providers:[]}),',
        "deletePlugin_and_authProviders_fallback",
    )
    content = must_replace(
        content,
        'searchMarketplace:async t=>\nf(v=>v.searchMarketplace($b(mia,{query:t.query??"",groupId:t.groupId??"",pageToken:t.pageToken??""}))),getMarketplaceCustomization:async t=>f(v=>v.getMarketplaceCustomization($b(oia,{id:t.id,includePreview:t.includePreview??!1}))),getMarketplaceBundles:async t=>f(v=>v.getMarketplaceBundles($b(lia,{ids:t.ids??[]}))),getMarketplaceFeatured:async()=>f(t=>t.getMarketplaceFeatured($b(nia,{}))),installCustomization:async t=>f(v=>v.installCustomization($b(pia,{id:t.id,bundleId:t.bundleId}))),',
        'searchMarketplace:async t=>{typeof window!=="undefined"&&!window.__agLoadMarketplaceCatalog&&(window.__agLoadMarketplaceCatalog=async()=>{if(window.__agMpCatalog)return window.__agMpCatalog;var r=await fetch("/marketplace_catalog.json");window.__agMpCatalog=await r.json();return window.__agMpCatalog});var _SMR=mia.file.messages.find(m=>m.name==="SearchMarketplaceResponse")||mia.file.messages[509];try{var _sr=await f(v=>v.searchMarketplace($b(mia,{query:t.query??"",groupId:t.groupId??"",pageToken:t.pageToken??""})));if(_sr&&_sr.customizations&&_sr.customizations.length>0)return _sr}catch(_e){}try{var _cat=await window.__agLoadMarketplaceCatalog(),_q=(t.query??"").trim().toLowerCase(),_gid=t.groupId??"",_all=(_cat.search&&_cat.search.customizations)||[],_grps=(_cat.search&&_cat.search.groups)||[];if(_gid){var _g=_grps.find(x=>x.id===_gid);_all=_g?_all.filter(x=>_g.memberIds.includes(x.id)):[]}if(_q){_all=_all.filter(x=>(x.displayName||"").toLowerCase().includes(_q)||(x.id||"").toLowerCase().includes(_q)||(x.description||"").toLowerCase().includes(_q))}return kf(_SMR,{customizations:_all,groups:_q||_gid?[]:_grps},{ignoreUnknownFields:!0})}catch(_e2){return kf(_SMR,{customizations:[],groups:[]},{ignoreUnknownFields:!0})}},getMarketplaceCustomization:async t=>{typeof window!=="undefined"&&!window.__agLoadMarketplaceCatalog&&(window.__agLoadMarketplaceCatalog=async()=>{if(window.__agMpCatalog)return window.__agMpCatalog;var r=await fetch("/marketplace_catalog.json");window.__agMpCatalog=await r.json();return window.__agMpCatalog});try{var _gr=await f(v=>v.getMarketplaceCustomization($b(oia,{id:t.id,includePreview:t.includePreview??!1})));if(_gr&&_gr.customization)return _gr}catch(_e){}var _cat=await window.__agLoadMarketplaceCatalog(),_det=(_cat.details&&_cat.details[t.id])||{customization:((_cat.search&&_cat.search.customizations)||[]).find(x=>x.id===t.id)};return kf(mia.file.messages.find(m=>m.name==="GetMarketplaceCustomizationResponse")||mia.file.messages[523],_det,{ignoreUnknownFields:!0})},getMarketplaceBundles:async t=>{try{return await f(v=>v.getMarketplaceBundles($b(lia,{ids:t.ids??[]})))}catch(_e){return kf(mia.file.messages.find(m=>m.name==="GetMarketplaceBundlesResponse")||mia.file.messages[507],{bundles:[],customizations:[]},{ignoreUnknownFields:!0})}},getMarketplaceFeatured:async()=>{try{return await f(t=>t.getMarketplaceFeatured($b(nia,{})))}catch(_e){return kf(mia.file.messages.find(m=>m.name==="GetMarketplaceFeaturedResponse")||mia.file.messages[520],{featured:[],customizations:[]},{ignoreUnknownFields:!0})}},installCustomization:async t=>{typeof window!=="undefined"&&!window.__agLoadMarketplaceCatalog&&(window.__agLoadMarketplaceCatalog=async()=>{if(window.__agMpCatalog)return window.__agMpCatalog;var r=await fetch("/marketplace_catalog.json");window.__agMpCatalog=await r.json();return window.__agMpCatalog});try{var _ir=await f(v=>v.installCustomization($b(pia,{id:t.id,bundleId:t.bundleId})));if(_ir&&_ir.success)return _ir}catch(_e){}try{var _cat=await window.__agLoadMarketplaceCatalog(),_c=((_cat.search&&_cat.search.customizations)||[]).find(x=>x.id===t.id),_pname=(t.id||"").split("/").pop(),_url=(_c&&_c.source&&_c.source.archive&&_c.source.archive.url)||"",_mp=(_c&&_c.marketplace)||"antigravity-plugins-official",_ver=(_c&&_c.version)||"1.0.0";if(window.Android&&window.Android.installMarketplacePlugin){var _jr=JSON.parse(window.Android.installMarketplacePlugin(_pname,_url,t.id,_mp,_ver));await new Promise(r=>setTimeout(r,300));return kf(mia.file.messages.find(m=>m.name==="InstallCustomizationResponse")||mia.file.messages[526],_jr,{ignoreUnknownFields:!0})}}catch(_e2){return kf(mia.file.messages.find(m=>m.name==="InstallCustomizationResponse")||mia.file.messages[526],{success:!1,message:String(_e2)},{ignoreUnknownFields:!0})}return kf(mia.file.messages.find(m=>m.name==="InstallCustomizationResponse")||mia.file.messages[526],{success:!1,message:"Marketplace install unavailable"},{ignoreUnknownFields:!0})},',
        "marketplace_rpcs_fallback",
    )
    content = must_replace(
        content,
        'function oS(a,b){return b.find(c=>Pwb(c.installedFrom,a))}',
        'function oS(a,b){return b.find(c=>Pwb(c.installedFrom,a)||(a?.id&&nS(c)===a.id.split("/").pop()))}',
        "oS_match_by_dir_fallback",
    )
    content = must_replace(
        content,
        '["Jetski Chat",{sections:[]}],["Developer",\n{sections:[]}]]',
        '["Automations",{sections:[]}],["Jetski Chat",{sections:[]}],["Developer",\n{sections:[]}]]',
        "Zsb_screens",
    )
    content = must_replace(
        content,
        '{screen:"Customizations"},{screen:"Developer"},{screen:"Running"},{screen:"Jetski Chat"},{screen:"Regroup Google3 Chats"}],Rpb="General;Appearance;Project 4K;Shortcuts;Notifications;Models;Customizations;Developer;Running".split(";")',
        '{screen:"Customizations"},{screen:"Automations"},{screen:"Developer"},{screen:"Running"},{screen:"Jetski Chat"},{screen:"Regroup Google3 Chats"}],Rpb="General;Appearance;Project 4K;Shortcuts;Notifications;Models;Customizations;Automations;Developer;Running".split(";")',
        "Qpb_Rpb_screens",
    )
    content = must_replace(
        content,
        '{screen:"Account"},{screen:"Customizations"},{screen:"Notifications"}',
        '{screen:"Account"},{screen:"Customizations"},{screen:"Automations"},{screen:"Notifications"}',
        "Czc_screens",
    )
    content = must_replace(
        content,
        'g=l=>{if(!c)return l;l=new Map(l);l.delete("Customizations");return l}',
        'g=l=>l',
        "keep_customizations_in_settings",
    )
    content = must_replace(
        content,
        'var bzb=()=>{var {authorities:a,selectedAuthority:b,setSelectedAuthority:c,selectedGeminiDir:d,selectedConfigDir:f}=qS(),g=hk().get("projectsCustomizations")?.showHeadCustomizationsInMainScreen??!1,h=(0,A.useMemo)(()=>g?["file:///google/src/files/head/depot/"]:[],[g]);return a.length===0?A.createElement(UP,{title:"Customizations"},A.createElement("div",{className:"flex items-center gap-2 text-sm text-muted-foreground"},A.createElement(yw,{size:14,className:"text-muted-foreground"}),"Connecting to language server...")):\nA.createElement(azb,{key:b,geminiDir:d,configDir:f,workspaceUris:h,tabs:a.map(k=>({id:k.authority,label:k.displayName})),selectedAuthority:b,onSelectAuthority:c})};',
        'var bzb=()=>A.createElement(EJb,null,A.createElement(A$b,null,A.createElement(Kyc,null)));',
        "bzb_replace_with_marketplace_Kyc",
    )
    content = must_replace(
        content,
        'case "Customizations":return A.createElement(bzb,null);',
        'case "Customizations":return A.createElement(bzb,null);case "Automations":return A.createElement(iMc,{sidecarType:"automation"});',
        "hzb_automations_screen",
    )
    content = must_replace(
        content,
        'A.createElement("div",{style:{display:c==="Customizations"?"block":"none"}},A.createElement(bzb,null))',
        'A.createElement("div",{style:{display:c==="Customizations"?"block":"none"}},A.createElement(bzb,null)),A.createElement("div",{style:{display:c==="Automations"?"block":"none"}},A.createElement(iMc,{sidecarType:"automation"}))',
        "desktop_settings_automations_screen",
    )
    content = must_replace(
        content,
        'var kG=()=>{var a=XF();return(0,A.useCallback)(b=>{a({to:"/",search:c=>{c={...c};delete c.cascadeId;delete c.focused;',
        'var kG=()=>{var a=XF();return(0,A.useCallback)(b=>{a({to:"/",search:c=>{c={...c};delete c.cascadeId;delete c.focused;delete c.settingsOpen;delete c.settingsScreen;delete c.settingsProjectId;delete c.settingsSection;',
        "kG_close_settings",
    )

    # Make Kyc/Fyc/w3/anb work seamlessly both inside Settings modal and on /customizations route
    old_Fyc_Kyc = (
        'function Fyc({tab:a,onSelect:b}){return A.createElement("nav",{"aria-label":"Customizations views",className:"agy-pill inline-flex items-center h-8 rounded-lg border border-border bg-secondary/20 p-0.5 gap-0.5 select-none","data-testid":"plugins-tabs"},Eyc.map(({tab:c,to:d,label:f})=>{var g=c===a;return A.createElement(WF,{key:c,to:d,activeOptions:{exact:!0},"aria-current":g?"page":void 0,onClick:()=>{g||b(c)},className:dj("agy-pill inline-flex items-center justify-center h-full px-2 rounded-md text-sm shrink-0 whitespace-nowrap no-underline transition-colors cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-primary",\n'
        'g?"bg-secondary text-foreground":"text-secondary-foreground hover:text-foreground"),"data-testid":`plugins-tab-${c}`},f)}))};const Gyc={source:"remote"};\n'
        'function Hyc({tab:a}){var {plugins:b,error:c,dismissError:d,refetch:f}=Uzb(),g=f5().trim(),h=g.trim().toLowerCase(),k=(0,A.useMemo)(()=>(b??[]).filter(l=>(a==="browse"||l.isAuthenticated)&&g5(h,l.displayName,l.name,l.description)),[b,a,h]);g=g?`No apps found for "${g}".`:a==="browse"?"No apps available.":"No apps connected yet. Connect one from Marketplace.";return A.createElement("div",{className:"flex flex-col gap-6"},A.createElement(Iyc,null),A.createElement(M3,{title:a==="browse"?"Apps":"Connected",\n'
        'count:b?k.length:void 0,isFirst:!0},b?A.createElement(A.Fragment,null,c&&A.createElement(dT,{title:"Error:",message:c,onDismiss:d}),k.length===0?A.createElement(Yxc,{message:g}):A.createElement("div",{className:"grid gap-2 grid-cols-1 sm:grid-cols-2"},k.map(l=>{var m=l.displayName||l.name,n=l.accounts.length>0?"Reconnect":"Connect";return A.createElement(w3,{key:l.name,name:m,description:l.description,logo:l.logo,params:{pluginId:l.name},search:Gyc,searchQuery:h,badge:a==="browse"&&l.isAuthenticated?\n'
        'A.createElement(u3,{variant:"muted"},"Connected"):void 0,action:!l.isAuthenticated&&l.authorizeUri?A.createElement(aB,{variant:"secondary",size:"xs","aria-label":`${n} ${m}`,onClick:()=>{Do(l.authorizeUri)}},n):null})}))):c?A.createElement(Xxc,{error:c,onRetry:()=>{f()}}):A.createElement(Wxc,{label:"Loading apps..."})))}\n'
        'function Iyc(){return A.createElement("div",{className:"flex gap-3 items-start px-4 py-3 rounded-xl border border-border"},A.createElement(lt,{name:"cloud",size:20,className:"shrink-0 mt-0.5 text-muted-foreground"}),A.createElement("div",{className:"flex flex-col gap-1"},A.createElement("p",{className:"text-sm font-medium text-foreground m-0"},"Apps connected to your account"),A.createElement("p",{className:"text-xs text-muted-foreground m-0"},"Sign in to an app once and your agent can use it on any device. Open an app to see the account it uses or to disconnect it.")))}\n'
        ';function Jyc({searchQuery:a,children:b}){var c=hk().get("customizations")?.showBundles??!1;a=ayc(a);$wc({enabled:c});return A.createElement(Zxc.Provider,{value:a},b)};function Kyc(){var {q:a}=cG({strict:!1}),b=OF({select:v=>v.location.pathname}),c=hk().get("customizations"),d=c?.showMarketplaceTab??!0,f=!d||b.startsWith("/customizations/installed")?"installed":"browse";b=c?.pluginSources??"local";var g=HW(),h=b==="remote",[k,l]=(0,A.useState)(()=>a??g.searchQuery),[m,n]=(0,A.useState)(a);m!==a&&(n(a),a!==void 0&&l(a));(0,A.useEffect)(()=>{g.searchQuery=k},[g,k]);var {logTabToggle:p,logSearch:r}=A3(),u=$0(k,800),t=(0,A.useRef)(k);(0,A.useEffect)(()=>{h||u===t.current||\n'
        '(t.current=u)&&r(f,u)},[u,f,h,r]);return A.createElement("div",{className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},A.createElement("div",{className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},A.createElement("div",{className:"sticky top-0 z-20 bg-background"},A.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 pt-8 pb-4 flex flex-col gap-4"},A.createElement("div",{className:"flex flex-wrap items-center justify-between gap-2 sm:gap-4"},\n'
        'A.createElement("h1",{className:"text-lg font-medium text-foreground select-none sm:px-3 m-0"},"Customizations"),A.createElement("div",{className:"flex items-center gap-4"},d&&A.createElement(Fyc,{tab:f,onSelect:p}))),A.createElement(Dyc,{searchQuery:k,onSearchQueryChange:l,placeholder:"Search customizations...",inputTestId:"customizations-search"}))),A.createElement("div",{className:"max-w-4xl w-full mx-auto px-4 sm:px-8 py-6"},A.createElement(Jyc,{searchQuery:h?"":k},A.createElement(gxc,{value:k},\n'
        'h?A.createElement(Hyc,{tab:f}):A.createElement(SF,null))))))}'
    )
    new_Fyc_Kyc = (
        'function Fyc({tab:a,onSelect:b,inRoute:inR=!0}){return A.createElement("nav",{"aria-label":"Customizations views",className:"agy-pill inline-flex items-center h-8 rounded-lg border border-border bg-secondary/20 p-0.5 gap-0.5 select-none","data-testid":"plugins-tabs"},Eyc.map(({tab:c,to:d,label:f})=>{var g=c===a,cls=dj("agy-pill inline-flex items-center justify-center h-full px-2 rounded-md text-sm shrink-0 whitespace-nowrap no-underline transition-colors cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-primary",g?"bg-secondary text-foreground":"text-secondary-foreground hover:text-foreground");return inR?A.createElement(WF,{key:c,to:d,activeOptions:{exact:!0},"aria-current":g?"page":void 0,onClick:()=>{g||b(c)},className:cls,"data-testid":`plugins-tab-${c}`},f):A.createElement("button",{key:c,type:"button","aria-current":g?"page":void 0,onClick:()=>{g||b(c)},className:dj(cls,"border-none bg-transparent"),"data-testid":`plugins-tab-${c}`},f)}))};const Gyc={source:"remote"};\n'
        'function Hyc({tab:a}){var {plugins:b,error:c,dismissError:d,refetch:f}=Uzb(),g=f5().trim(),h=g.trim().toLowerCase(),k=(0,A.useMemo)(()=>(b??[]).filter(l=>(a==="browse"||l.isAuthenticated)&&g5(h,l.displayName,l.name,l.description)),[b,a,h]);g=g?`No apps found for "${g}".`:a==="browse"?"No apps available.":"No apps connected yet. Connect one from Marketplace.";return A.createElement("div",{className:"flex flex-col gap-6"},A.createElement(Iyc,null),A.createElement(M3,{title:a==="browse"?"Apps":"Connected",\n'
        'count:b?k.length:void 0,isFirst:!0},b?A.createElement(A.Fragment,null,c&&A.createElement(dT,{title:"Error:",message:c,onDismiss:d}),k.length===0?A.createElement(Yxc,{message:g}):A.createElement("div",{className:"grid gap-2 grid-cols-1 sm:grid-cols-2"},k.map(l=>{var m=l.displayName||l.name,n=l.accounts.length>0?"Reconnect":"Connect";return A.createElement(w3,{key:l.name,name:m,description:l.description,logo:l.logo,params:{pluginId:l.name},search:Gyc,searchQuery:h,badge:a==="browse"&&l.isAuthenticated?\n'
        'A.createElement(u3,{variant:"muted"},"Connected"):void 0,action:!l.isAuthenticated&&l.authorizeUri?A.createElement(aB,{variant:"secondary",size:"xs","aria-label":`${n} ${m}`,onClick:()=>{Do(l.authorizeUri)}},n):null})}))):c?A.createElement(Xxc,{error:c,onRetry:()=>{f()}}):A.createElement(Wxc,{label:"Loading apps..."})))}\n'
        'function Iyc(){return A.createElement("div",{className:"flex gap-3 items-start px-4 py-3 rounded-xl border border-border"},A.createElement(lt,{name:"cloud",size:20,className:"shrink-0 mt-0.5 text-muted-foreground"}),A.createElement("div",{className:"flex flex-col gap-1"},A.createElement("p",{className:"text-sm font-medium text-foreground m-0"},"Apps connected to your account"),A.createElement("p",{className:"text-xs text-muted-foreground m-0"},"Sign in to an app once and your agent can use it on any device. Open an app to see the account it uses or to disconnect it.")))}\n'
        ';function Jyc({searchQuery:a,children:b}){var c=hk().get("customizations")?.showBundles??!1;a=ayc(a);$wc({enabled:c});return A.createElement(Zxc.Provider,{value:a},b)};'
        'var __agSafeMd=({markdown:md,className:cls,baseDirUri:bd})=>A.createElement(h3,{cascadeId:void 0,markdown:md,context:"artifact",disableBrowserPreview:!0,className:cls,parseFrontmatter:!0,baseDirUri:bd});'
        'function Kyc(){var {q:a}=cG({strict:!1}),b=OF({select:v=>v.location.pathname}),inR=b.startsWith("/customizations"),mdR=inR?Q3:__agSafeMd,[locTab,setLocTab]=(0,A.useState)("browse"),[det,setDet]=(0,A.useState)(null),c=hk().get("customizations"),d=c?.showMarketplaceTab??!0,f=!d?"installed":inR?(b.startsWith("/customizations/installed")?"installed":"browse"):locTab;b=c?.pluginSources??"local";var g=HW(),h=b==="remote",[k,l]=(0,A.useState)(()=>a??g.searchQuery),[m,n]=(0,A.useState)(a);m!==a&&(n(a),a!==void 0&&l(a));(0,A.useEffect)(()=>{g.searchQuery=k},[g,k]);'
        '(0,A.useEffect)(()=>{if(!inR){window.__agCustSelect=setDet;window.__agCustTab=setLocTab;return()=>{if(window.__agCustSelect===setDet)window.__agCustSelect=null;if(window.__agCustTab===setLocTab)window.__agCustTab=null}}},[inR]);'
        'var {logTabToggle:p,logSearch:r}=A3(),u=$0(k,800),t=(0,A.useRef)(k);(0,A.useEffect)(()=>{h||u===t.current||(t.current=u)&&r(f,u)},[u,f,h,r]);'
        'var onTab=tb=>{inR||setLocTab(tb);setDet(null);p(tb)};'
        'if(!inR&&det)return A.createElement("div",{className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},A.createElement("div",{className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},A.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 py-4"},A.createElement("button",{type:"button",onClick:()=>setDet(null),className:"mb-4 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground bg-transparent border-none cursor-pointer p-0"},A.createElement(lt,{name:"arrow_back",size:16}),"Customizations"),det.bundleId?A.createElement(exc,{bundleId:det.bundleId}):A.createElement(ekc,{installDir:det.installDir,catalogId:det.catalogId,onUninstalled:()=>setDet(null),onDisconnected:()=>setDet(null),MarkdownRenderer:mdR}))));'
        'return A.createElement("div",{className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},A.createElement("div",{className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},A.createElement("div",{className:"sticky top-0 z-20 bg-background"},A.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 pt-6 pb-4 flex flex-col gap-4"},A.createElement("div",{className:"flex flex-wrap items-center justify-between gap-2 sm:gap-4"},A.createElement("h1",{className:"text-lg font-medium text-foreground select-none sm:px-3 m-0"},"Customizations"),A.createElement("div",{className:"flex items-center gap-4"},d&&A.createElement(Fyc,{tab:f,onSelect:onTab,inRoute:inR}))),A.createElement(Dyc,{searchQuery:k,onSearchQueryChange:l,placeholder:"Search customizations...",inputTestId:"customizations-search"}))),A.createElement("div",{className:"max-w-4xl w-full mx-auto px-4 sm:px-8 py-6"},A.createElement(Jyc,{searchQuery:h?"":f==="browse"?k:""},A.createElement(gxc,{value:k},h?A.createElement(Hyc,{tab:f}):inR?A.createElement(SF,null):(f==="browse"?A.createElement(xyc,{MarkdownRenderer:mdR}):A.createElement(hyc,{MarkdownRenderer:mdR})))))))}'
    )
    content = must_replace(content, old_Fyc_Kyc, new_Fyc_Kyc, "Fyc_Kyc_dual_mode")

    content = must_replace(
        content,
        'g=Ql(t=>t.newConvoResourceSelector.selectedEnvironmentByProject),h=c.get("currentWorkspace")?.workspaceUri??void 0,k=a||b?.project?.id;a=(0,A.useMemo)(()=>k?d.getProjectStateProvider(k):void 0,[k,d]);a=Ul(a);var l=k?g[k]:void 0',
        'g=Ql(t=>t.newConvoResourceSelector?.selectedEnvironmentByProject),h=c.get("currentWorkspace")?.workspaceUri??void 0,k=a||b?.project?.id;a=(0,A.useMemo)(()=>k?d.getProjectStateProvider(k):void 0,[k,d]);a=Ul(a);var l=k&&g?g[k]:void 0',
        "RW_safe_redux",
    )

    content = must_replace(
        content,
        'return OF({select:c=>{c=c.matches.find(d=>a.from?a.from===d.routeId:d.id===b);RF(!((a.shouldThrow??!0)&&!c),`Could not find ${a.from?`an active match from "${a.from}"`:"a nearest match!"}`);if(c!==void 0)return a.select?a.select(c):c},structuralSharing:a.structuralSharing})}',
        'return OF({select:c=>{c=c.matches.find(d=>a.from?a.from===d.routeId:d.id===b)||c.matches[c.matches.length-1]||{params:{},_strictParams:{},search:{},_strictSearch:{}};if(c!==void 0)return a.select?a.select(c):c},structuralSharing:a.structuralSharing})}',
        "w6a_fallback_outside_outlet",
    )

    content = must_replace(
        content,
        'A.createElement(WF,{to:d,params:f,search:g,onClick:m,"aria-label":p,className:"rounded-sm after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary text-left"}',
        'A.createElement(WF,{to:d,params:f,search:g,onClick:ev=>{m?.(ev);if(typeof window!=="undefined"&&window.__agCustSelect){ev.preventDefault();window.__agCustSelect({installDir:d==="/customizations/plugins/$pluginId"?f?.pluginId:void 0,catalogId:g?.id||(f?YE(f):void 0)})}},"aria-label":p,className:"rounded-sm after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary text-left"}',
        "w3_card_detail_in_settings",
    )
    content = must_replace(
        content,
        'A.createElement(WF,{to:"/customizations/marketplaces/$marketplace/bundles/$bundleId",params:{marketplace:a.marketplace,bundleId:a.id},className:"rounded-sm after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary"}',
        'A.createElement(WF,{to:"/customizations/marketplaces/$marketplace/bundles/$bundleId",params:{marketplace:a.marketplace,bundleId:a.id},onClick:ev=>{if(typeof window!=="undefined"&&window.__agCustSelect){ev.preventDefault();window.__agCustSelect({bundleId:a.id})}},className:"rounded-sm after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary"}',
        "bundle_detail_in_settings",
    )
    content = must_replace(
        content,
        'A.createElement(WF,{to:"/customizations",search:c?{q:b}:void 0,className:d},f)',
        'A.createElement(WF,{to:"/customizations",search:c?{q:b}:void 0,onClick:ev=>{if(typeof window!=="undefined"&&window.__agCustTab){ev.preventDefault();window.__agCustTab("browse")}},className:d},f)',
        "browse_in_settings",
    )
    content = must_replace(
        content,
        'var hX=()=>{var {getActiveEditor:a}=(0,A.useContext)(rP),{logEvent:b}=wE(),c=kG();return(0,A.useCallback)((d,f)=>{b("AGENT_MANAGER_HOME");c({project:d,clearProject:f?.clearProject});queueMicrotask(()=>{var g=a();g&&document.hasFocus()&&jjb(g)})},[a,b,c])};',
        'var hX=()=>{var {getActiveEditor:a}=(0,A.useContext)(rP),{logEvent:b}=wE(),c=kG(),hideS=(0,A.useContext)(Zvb)?.triggerHideSettingsModal;return(0,A.useCallback)((d,f)=>{hideS?.();b("AGENT_MANAGER_HOME");c({project:d,clearProject:f?.clearProject});queueMicrotask(()=>{var g=a();g&&document.hasFocus()&&jjb(g)})},[a,b,c,hideS])};',
        "hX_close_settings_on_prompt",
    )
    content = must_replace(
        content,
        'P6a=()=>{var a=XF(),b=J6a();return(0,A.useCallback)(c=>{var d=b();a({to:"/sidecars/$sidecarId",params:{sidecarId:c},...(d?{search:d}:{})})},[a,b])}',
        'P6a=()=>{var a=XF(),b=J6a(),hideS=(0,A.useContext)(Zvb)?.triggerHideSettingsModal;return(0,A.useCallback)(c=>{hideS?.();var d=b();a({to:"/sidecars/$sidecarId",params:{sidecarId:c},...(d?{search:d}:{})})},[a,b,hideS])}',
        "P6a_close_settings",
    )
    content = must_replace(
        content,
        'R6a=()=>{var a=XF(),b=J6a();return(0,A.useCallback)(c=>{var d=b();a({to:"/sidecars/triggers/$triggerId",params:{triggerId:c},...(d?{search:d}:{})})},[a,b])}',
        'R6a=()=>{var a=XF(),b=J6a(),hideS=(0,A.useContext)(Zvb)?.triggerHideSettingsModal;return(0,A.useCallback)(c=>{hideS?.();var d=b();a({to:"/sidecars/triggers/$triggerId",params:{triggerId:c},...(d?{search:d}:{})})},[a,b,hideS])}',
        "R6a_close_settings",
    )
    content = must_replace(
        content,
        'initialPath:b?b.fsPath:$g?"C:/":"/"',
        'initialPath:"/sdcard"',
        "default_sdcard_path",
    )

    # 4. Enable Voice Mode (Start Voice mode), Microphone (Audio Streaming), and Permissions Selector (Turbo)
    content = must_replace(
        content,
        'inputComponent:{feature:{hideAgentSelectorIfEmpty:!1,hideInSubagentConversation:!1,showPermissionsSelector:!1,disableInlineCompletion:!0,showInternalModesDropdown:!1,\nenableMicrophone:f,showAutonomousModeToggle:!1,showModelUsage:!0,enableFileSystemCategoriesInMentions:!1,enableGoogleWorkspaceMentions:!1,enableVoiceRelay:!1,enablePasteTextAsSnippet:!1}',
        'inputComponent:{feature:{hideAgentSelectorIfEmpty:!1,hideInSubagentConversation:!1,showPermissionsSelector:!0,disableInlineCompletion:!0,showInternalModesDropdown:!1,\nenableMicrophone:!0,showAutonomousModeToggle:!0,showModelUsage:!0,enableFileSystemCategoriesInMentions:!1,enableGoogleWorkspaceMentions:!1,enableVoiceRelay:!0,enablePasteTextAsSnippet:!0}',
        "F8c_inputComponent",
    )
    content = must_replace(
        content,
        '{runtimeGating:{host:[Xi.Web.Desktop,Xi.Electron],customFlag:"enable-permissions-selector"},feature:{showPermissionsSelector:!0}}',
        '{runtimeGating:{customFlag:"enable-permissions-selector"},feature:{showPermissionsSelector:!0}}',
        "F8c_showPermissionsSelector_gating",
    )
    content = must_replace(
        content,
        'inputComponent:{feature:{disableInlineCompletion:!0,showInternalModesDropdown:!1,enableMicrophone:!1,enableVoiceRelay:!1},applyAll:',
        'inputComponent:{feature:{disableInlineCompletion:!0,showInternalModesDropdown:!1,showPermissionsSelector:!0,enableMicrophone:!0,enableVoiceRelay:!0},applyAll:',
        "o8c_inputComponent",
    )
    content = must_replace(
        content,
        'remoteConversationCapabilities:{feature:{fork:!1,conversationMentions:!1,sideQuestion:!1,subagentMessaging:!1,revealInFileManager:!1,extensions:!1,connectors:!1,terminal:!1,browser:!1,localPaths:!1,voice:!1}',
        'remoteConversationCapabilities:{feature:{fork:!1,conversationMentions:!1,sideQuestion:!1,subagentMessaging:!1,revealInFileManager:!1,extensions:!1,connectors:!1,terminal:!0,browser:!1,localPaths:!1,voice:!0}',
        "remoteConversationCapabilities",
    )
    content = must_replace(
        content,
        'Ra=[...(x9()?["enable-conversation-selection-quoting"]:[])',
        'Ra=["enable-voice-relay","enable-voice-remote","enable-permissions-selector","enable-paste-text-as-snippet","jetski-autonomous-mode",...(x9()?["enable-conversation-selection-quoting"]:[])',
        "P8c_Ra_customFlags",
    )
    content = must_replace(
        content,
        'let aR={isAvailable:!1,voiceState:"off",voiceConvId:null,muted:!1,liveLines:$Q,speed:1};',
        'let aR={isAvailable:!0,voiceState:"off",voiceConvId:null,muted:!1,liveLines:$Q,speed:1};',
        "aR_isAvailable",
    )
    content = must_replace(
        content,
        'some(k=>k.kind==="audioinput");b(h)}else b(!1)',
        'some(k=>k.kind==="audioinput");b(!0)}else b(!0)',
        "ccb_hasMicrophone",
    )
    content = must_replace(
        content,
        'enabled:kj(a,"enable-audio-transcription")}',
        'enabled:new Ni(!0)}',
        "z6c_audioTranscription",
    )
    content = must_replace(
        content,
        'var F=!m&&b;t=(w||t)&&v;v=a&&!b&&!x?.isOn;c=p==="idle"&&!m&&!C;d=d||k||l||n;k=!!x&&!x.isOn&&!d&&!b&&c;',
        'var F=!m&&b;t=!!v;v=a&&!b&&!x?.isOn;c=p==="idle"&&!C;d=d||k||l||n;k=!!x&&!x.isOn&&!d&&!b&&c;',
        "Suc_micAndVoice",
    )
    content = must_replace(
        content,
        "puc({cascadeId:M,planModel:y?.value,enabled:!xa&&me&&(la??AP).voice})",
        "puc({cascadeId:M,planModel:y?.value||1018,enabled:!xa&&me&&(la??AP).voice})",
        "puc_planModel_fallback",
    )
    content = must_replace(
        content,
        '"shrink-0 whitespace-nowrap @max-[400px]/input-footer:w-7 @max-[400px]/input-footer:justify-center @max-[400px]/input-footer:!p-1.5",G&&"!cursor-not-allowed opacity-50 hover:!bg-transparent")},A.createElement(Wrc,{preset:c,className:"@max-[400px]/input-footer:!h-4 @max-[400px]/input-footer:!w-4"}),A.createElement("span",{className:"@max-[400px]/input-footer:hidden"},y),!G&&A.createElement(lt,{name:b==="top"?"keyboard_arrow_up":"keyboard_arrow_down",size:14,className:"shrink-0 @max-[400px]/input-footer:hidden"})',
        '"shrink-0 whitespace-nowrap",G&&"!cursor-not-allowed opacity-50 hover:!bg-transparent")},A.createElement(Wrc,{preset:c,className:""}),A.createElement("span",{className:""},y),!G&&A.createElement(lt,{name:b==="top"?"keyboard_arrow_up":"keyboard_arrow_down",size:14,className:"shrink-0"})',
        "Yrc_mobilePermissionsBadge",
    )

    with open(file_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)

    print("[web-patch] Successfully patched main.js (v2.29.0 Android UI + Settings Customizations/Automations + Overview + AuxPane v2)")
    return True


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "main.js")
    if not patch_main(path):
        sys.exit(1)
