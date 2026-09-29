package tricatch.oe.proxy.util;

import tricatch.oe.proxy.cfg.attr.VirtualDomain;
import tricatch.oe.proxy.cfg.attr.VirtualLocation;
import tricatch.oe.proxy.server.VirtualHosts;
import tricatch.oe.proxy.server.VirtualPath;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;

public class VirtualHostUtil {

    public static VirtualHosts convert(List<VirtualDomain> virtualDomains) throws MalformedURLException {

        VirtualHosts virtualHosts = new VirtualHosts();

        for (int v = 0; v < virtualDomains.size(); v++) {

            VirtualDomain virtualDomain = virtualDomains.get(v);

            String domain = virtualDomain.getDomain();

            List<VirtualPath> virtualPathListExact = new ArrayList<>();
            List<VirtualPath> virtualPathListPattern = new ArrayList<>();

            List<VirtualLocation> virtualLocationList = virtualDomain.getLocation();
            if (virtualLocationList == null) {
                throw new IllegalArgumentException("domain '" + domain + "': 'location' is required");
            }
            for (int p = 0; p < virtualLocationList.size(); p++) {

                VirtualLocation virtualLocation = virtualLocationList.get(p);

                if (virtualLocation.getHost() == null || virtualLocation.getHost().isBlank()) {
                    throw new IllegalArgumentException("domain '" + domain + "': location[" + p + "].host is required");
                }
                URL target = URI.create(virtualLocation.getHost()).toURL();
                List<String> pathList = virtualLocation.getPath();
                if (pathList == null) {
                    throw new IllegalArgumentException("domain '" + domain + "': location[" + p + "].path is required");
                }

                for (int u = 0; u < pathList.size(); u++) {

                    String path = pathList.get(u);

                    VirtualPath virtualPath = new VirtualPath();
                    virtualPath.setPath(path);
                    virtualPath.setTarget(target);

                    if( path.contains("*") ){
                        virtualPathListPattern.add(virtualPath);
                    }else{
                        virtualPathListExact.add(virtualPath);
                    }

                    List<String> addHeader = new ArrayList<>();
                    List<String> removeHeader = new ArrayList<>();

                    List<String> headers = virtualLocation.getHeader();
                    if( headers!=null && !headers.isEmpty() ){
                        for(int h=0;h<headers.size();h++){
                            String header = headers.get(h);
                            if( header.startsWith("--") ){
                                header = header.substring(2);
                                removeHeader.add(header);
                            } else {
                                addHeader.add(header);
                            }
                        }
                    }

                    virtualPath.setAddHeader(addHeader);
                    virtualPath.setRemoveHeader(removeHeader);
                }
            }

            // sort by path - reverse
            Comparator<VirtualPath> virtualPathcomparator = new Comparator<VirtualPath>() {
                @Override
                public int compare(VirtualPath o1, VirtualPath o2) {
                    return o2.getPath().compareTo(o1.getPath());
                }
            };

            Collections.sort(virtualPathListExact, virtualPathcomparator);
            Collections.sort(virtualPathListPattern, virtualPathcomparator);

            List<VirtualPath> virtualPathList = new ArrayList<>();
            virtualPathList.addAll(virtualPathListExact);
            virtualPathList.addAll(virtualPathListPattern);

            virtualHosts.put(domain, virtualPathList);
        }

        return virtualHosts;
    }
}
